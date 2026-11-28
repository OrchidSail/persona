package db

import (
	"context"
	"crypto/sha256"
	"errors"
	"fmt"
	"strings"
	"time"

	"github.com/jackc/pgx/v5"
)

// What an extension may read of the assistant's sensitive information (#13246, migrations/manager/
// 20261122225047_assistant_extension_trust.sql). The manager checks the values; the store keeps what it is given.

// ExtensionTrust is one extension's: whether it is the user's own, and the sensitive categories it was granted.
type ExtensionTrust struct {
	Extension  string
	Trust      string // "owner" or "third_party"; "" when no row says
	Categories []string
	Revision   string
}

// DefaultExtensionTrustRevision is the baseline for an extension with no stored trust or grants.
const DefaultExtensionTrustRevision = "unset"

// ErrExtensionTrustMoved means a replacement's expected revision no longer matches.
var ErrExtensionTrustMoved = errors.New("extension trust changed")

// ExtensionTrustOf reads one extension's trust level. An absent row has an empty level.
func (d *DB) ExtensionTrustOf(ctx context.Context, extension string) (string, error) {
	var trust string
	err := d.pool.QueryRow(ctx, `SELECT trust FROM assistant_extension_trust WHERE extension_id = $1`, extension).Scan(&trust)
	if errors.Is(err, pgx.ErrNoRows) {
		return "", nil
	}
	return trust, err
}

// ExtensionTrusts are every extension a trust or a grant was kept for, by extension id.
func (d *DB) ExtensionTrusts(ctx context.Context) (map[string]ExtensionTrust, error) {
	return extensionTrusts(ctx, d.pool, "")
}

// Read trust and grants from one statement snapshot, including older grant rows without a trust row.
func extensionTrusts(ctx context.Context, q rowQuerier, extension string) (map[string]ExtensionTrust, error) {
	rows, err := q.Query(ctx, `
		SELECT extension_id, COALESCE(t.trust, ''), COALESCE(t.updated_at, 'epoch'::timestamptz), g.categories
		FROM assistant_extension_trust t
		FULL JOIN (
			SELECT extension_id, array_agg(category ORDER BY category) AS categories
			FROM assistant_extension_grants
			WHERE ($1 = '' OR extension_id = $1)
			GROUP BY extension_id
		) g USING (extension_id)
		WHERE ($1 = '' OR extension_id = $1)`, extension)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := map[string]ExtensionTrust{}
	for rows.Next() {
		var t ExtensionTrust
		var updated time.Time
		if err := rows.Scan(&t.Extension, &t.Trust, &updated, &t.Categories); err != nil {
			return nil, err
		}
		// PostgreSQL text cannot contain NUL. Include the state so legacy writes and grant-only rows have a revision too.
		state := fmt.Sprintf("%d\x00%s\x00%s", updated.UnixMicro(), t.Trust, strings.Join(t.Categories, "\x00"))
		t.Revision = fmt.Sprintf("%x", sha256.Sum256([]byte(state)))
		out[t.Extension] = t
	}
	return out, rows.Err()
}

// ForgetExtensionTrust drops extension's trust and grants (it was removed).
func (d *DB) ForgetExtensionTrust(ctx context.Context, extension string) error {
	return d.withExtensionTrustWrite(ctx, extension, func(tx pgx.Tx) error {
		if _, err := tx.Exec(ctx, `DELETE FROM assistant_extension_grants WHERE extension_id = $1`, extension); err != nil {
			return err
		}
		_, err := tx.Exec(ctx, `DELETE FROM assistant_extension_trust WHERE extension_id = $1`, extension)
		return err
	})
}

// SetExtensionTrust keeps extension's trust and replaces its granted categories with cats, as by says (the user).
func (d *DB) SetExtensionTrust(ctx context.Context, extension, trust string, cats []string, by string) error {
	_, err := d.ReplaceExtensionTrust(ctx, extension, trust, cats, by, nil)
	return err
}

// ReplaceExtensionTrust returns the state kept by the transaction, or the current state on a revision conflict.
func (d *DB) ReplaceExtensionTrust(ctx context.Context, extension, trust string, cats []string, by string, expected *string) (ExtensionTrust, error) {
	if extension == "" {
		return ExtensionTrust{}, errors.New("extension trust: no extension")
	}
	if cats == nil {
		cats = []string{} // NULL would match no row to delete
	}
	var state ExtensionTrust
	err := d.withExtensionTrustWrite(ctx, extension, func(tx pgx.Tx) error {
		if expected != nil {
			all, err := extensionTrusts(ctx, tx, extension)
			if err != nil {
				return err
			}
			var found bool
			state, found = all[extension]
			if !found {
				state = ExtensionTrust{Extension: extension, Revision: DefaultExtensionTrustRevision}
			}
			if *expected != state.Revision {
				return ErrExtensionTrustMoved
			}
		}
		if _, err := tx.Exec(ctx, `INSERT INTO assistant_extension_trust (extension_id, trust, updated_at)
			VALUES ($1, $2, clock_timestamp())
			ON CONFLICT (extension_id) DO UPDATE SET trust = EXCLUDED.trust,
				updated_at = GREATEST(EXCLUDED.updated_at, assistant_extension_trust.updated_at + interval '1 microsecond')`,
			extension, trust); err != nil {
			return err
		}
		if _, err := tx.Exec(ctx, `DELETE FROM assistant_extension_grants WHERE extension_id = $1 AND NOT (category = ANY($2::text[]))`,
			extension, cats); err != nil {
			return err
		}
		if _, err := tx.Exec(ctx, `INSERT INTO assistant_extension_grants (extension_id, category, granted_by)
			SELECT $1, c, $3 FROM unnest($2::text[]) AS c ON CONFLICT (extension_id, category) DO NOTHING`, extension, cats, by); err != nil {
			return err
		}
		all, err := extensionTrusts(ctx, tx, extension)
		state = all[extension]
		return err
	})
	return state, err
}

// Serialize all writers, including first edits and removal where there may be no trust row to lock.
func (d *DB) withExtensionTrustWrite(ctx context.Context, extension string, write func(pgx.Tx) error) error {
	return d.Transaction(ctx, func(tx pgx.Tx) error {
		if _, err := tx.Exec(ctx, `SELECT pg_advisory_xact_lock(hashtextextended($1, 0))`, "extension_trust:"+extension); err != nil {
			return err
		}
		return write(tx)
	})
}
