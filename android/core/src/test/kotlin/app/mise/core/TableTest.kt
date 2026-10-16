package app.mise.core

/** Runs every row and reports all failures at once, so one bad row doesn't hide the others. */
fun <T> table(rows: List<T>, describe: (T) -> String, check: (T) -> Pair<Any?, Any?>) {
    val failures = rows.mapNotNull { row ->
        val (expected, actual) = check(row)
        if (expected == actual) null else "  ${describe(row)}: expected <$expected> but was <$actual>"
    }
    if (failures.isNotEmpty()) {
        throw AssertionError("${failures.size} of ${rows.size} rows failed:\n" + failures.joinToString("\n"))
    }
}
