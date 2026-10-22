# Room Schema Migrations

> **Severity**: P0 — crash on open after app update, or silent destruction of user data.
>
> Symptom: works on fresh install, crashes on upgrade (`Migration didn't properly
> handle`), or user data vanishes after update.

---

## Cause

1. Entity edited but `version` not bumped → immediate `IllegalStateException`.
2. `fallbackToDestructiveMigration()` left in the release build → the "fix" becomes a
   data wipe.
3. Migration SQL diverges from what Room expects the schema to be (missing NOT NULL,
   missing index) → validation fails on open.

---

## Fix Patterns

### Always: export schema, bump version, write the migration

```kotlin
@Database(
    entities = [HomeEntity::class],
    version = 3,
    exportSchema = true,           // committed to VCS
)
```

```kotlin
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("""
            ALTER TABLE home_items ADD COLUMN pinned INTEGER NOT NULL DEFAULT 0
        """.trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS index_home_items_updatedAt ON home_items(updatedAt)")
    }
}
```

### Test the path, not the hope

```kotlin
@Test
fun migrate2To3() {
    helper.createDatabase(TEST_DB, 2).apply {
        execSQL("INSERT INTO home_items (id, title) VALUES (1, 'old')")
        close()
    }
    helper.runMigrationsAndValidate(TEST_DB, 3, true, MIGRATION_2_3)
}
```

`runMigrationsAndValidate` fails when the migrated schema does not match Room's
expectation — this is the test that saves production data.

### Renames and copies

SQLite `ALTER TABLE ... RENAME` is fine; changing type/nullability requires the
copy-table dance (`new table → INSERT SELECT → drop → rename`). Prefer additive
columns with defaults.

---

## Prevention

- Schema JSON committed in the same PR as the change (see
  [../backend/database.md](../backend/database.md))
- `fallbackToDestructiveMigration` banned in release; only ever behind a debug check
- Migration test required for every version bump — enforced in review, and by CI if
  the module has androidTest capacity
- Data-bearing columns are never dropped without a reviewed decision recorded in this
  file

---

## Real-World Variants

| Variant                                  | Handling                                  |
| ---------------------------------------- | ----------------------------------------- |
| Add nullable column                      | `ALTER TABLE ... ADD COLUMN x TEXT`       |
| Add non-null column                      | `ADD COLUMN x TEXT NOT NULL DEFAULT ''`   |
| Add table                                | `CREATE TABLE` matching entity exactly    |
| Rename column                            | copy-table dance (or keep the old name)   |
| Delete column                            | copy-table dance — needs explicit review  |

---

## Related

- [../shared/timestamp.md](../shared/timestamp.md) — units often change during
  migrations; check them at the same time
- [../guides/cross-layer-thinking-guide.md](../guides/cross-layer-thinking-guide.md) —
  semantic change audit for the migrated field
