# Database

> Room (Android) or SQLDelight (KMP). Schema discipline, tested migrations, no silent
> data loss.

TODO(spec): pick ONE per project — Room for Android-only, SQLDelight for KMP targets —
and delete the other section.

---

## Room Rules

1. **`exportSchema = true`** on every `@Database`, with the schema directory committed:

```kotlin
@Database(entities = [HomeEntity::class], version = 2, exportSchema = true)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun homeDao(): HomeDao
}
```

2. **Every migration is written and tested.** `fallbackToDestructiveMigration()` is
   banned in release builds — silent user-data wipes are P0:

```kotlin
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE home_items ADD COLUMN pinned INTEGER NOT NULL DEFAULT 0")
    }
}
```

   Test with `MigrationTestHelper` (androidTest). See
   [../big-question/room-schema-migrations.md](../big-question/room-schema-migrations.md).

3. **DAOs return `Flow`** for queries the UI observes; suspend one-shots for writes.
4. **Indices for every query predicate** — `@Entity(indices = [Index("updatedAt")])`
   when a column is filtered/sorted. Ships with a query-plan sanity check on large
   tables.
5. **Timestamps are epoch milliseconds (`Long`)** — see [../shared/timestamp.md](../shared/timestamp.md).
   Booleans are `INTEGER` via converters; enums via name-string converters with an
   unknown-value default.
6. **Transactions** for multi-table writes: `@Transaction` on the DAO method or
   `withTransaction { }` in the repository — see the consistency guide
   [../guides/cross-layer-thinking-guide.md](../guides/cross-layer-thinking-guide.md).
7. **Room tests** run against an in-memory DB: DAO round-trips, converters, and
   migration paths.

---

## Entity and DAO Example

```kotlin
@Entity(
    tableName = "home_items",
    indices = [Index("updatedAt")],
)
data class HomeEntity(
    @PrimaryKey val id: Long,
    val title: String,
    val status: String,          // stored as name; see converter
    val pinned: Boolean,
    /** Epoch millis, UTC. */
    val updatedAt: Long,
)

@Dao
interface HomeDao {
    @Query("SELECT * FROM home_items ORDER BY pinned DESC, updatedAt DESC")
    fun observeAll(): Flow<List<HomeEntity>>

    @Query("SELECT * FROM home_items WHERE id = :id")
    suspend fun findById(id: Long): HomeEntity?

    @Upsert
    suspend fun upsertAll(items: List<HomeEntity>)

    @Transaction
    suspend fun replaceAll(items: List<HomeEntity>) {
        clearAll()
        upsertAll(items)      // one transaction: list never shows empty mid-swap
    }
}
```

Converters tolerate unknown stored values:

```kotlin
class Converters {
    @TypeConverter fun statusToString(v: HomeStatus): String = v.name
    @TypeConverter fun stringToStatus(raw: String): HomeStatus = HomeStatus.fromWire(raw)
}
```

---

## SQLDelight Rules (KMP)

1. **`.sq` files per table**, queries named by read/write intent
   (`selectAll`, `upsertHomeItem`):

```sql
-- Home.sq
CREATE TABLE homeItem (
    id INTEGER NOT NULL PRIMARY KEY,
    title TEXT NOT NULL,
    updatedAt INTEGER NOT NULL   -- epoch millis, UTC
);

selectAll:
SELECT * FROM homeItem ORDER BY updatedAt DESC;

upsertHomeItem:
INSERT OR REPLACE INTO homeItem(id, title, updatedAt) VALUES (?, ?, ?);
```

2. **`verifyMigrations = true`** and committed `.sqm` migration files; one migration
   file per version step.
3. **Drivers are injected** (`AndroidSqliteDriver` / native driver) from DI; no
   driver construction inside repositories.

---

## Shared Schema Discipline

- [ ] New column → new version + migration + migration test
- [ ] Renamed column → migration with copy, not destructive rebuild
- [ ] Enum/boolean columns tolerate unknown stored values
- [ ] Schema JSON / `.sqm` files committed in the same PR as the change
- [ ] Data-bearing tables never dropped without an explicit, reviewed decision
- [ ] Query performance sanity-checked on representative data volume

---

## Anti-Patterns

- **Schema edited, version untouched** — runtime crash on open.
- **Business logic in SQL strings** — move decisions to Kotlin; SQL fetches.
- **`ALLOW_ALL` converters** — `TypeConverter` that round-trips arbitrary objects via
  JSON hides schema from the DB.
- **Main-thread queries** — Room enforces; never `allowMainThreadQueries()`.
