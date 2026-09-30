import org.flywaydb.core.Flyway;

/** Test harness only: migration/repair without starting the application or initializers. */
class IsolatedFlyway {
    public static void main(String[] args) {
        String url = System.getenv("VALIDATION_DB_URL");
        String database = System.getenv("VALIDATION_DB_NAME");
        if (args.length != 2 || !(args[0].equals("migrate") || args[0].equals("repair"))
                || !args[1].matches("[23]") || database == null
                || !database.matches("[a-z0-9_]+") || url == null
                || !url.matches("jdbc:mariadb://127\\.0\\.0\\.1:[0-9]+/" + database)
                || url.contains(":3306/")) {
            throw new IllegalArgumentException("Only an explicitly verified isolated loopback database is allowed");
        }
        var flyway = Flyway.configure()
                .dataSource(url, System.getenv("VALIDATION_DB_USER"), System.getenv("VALIDATION_DB_PASSWORD"))
                .locations("filesystem:" + System.getenv("VALIDATION_MIGRATIONS"))
                .target(args[1]).baselineOnMigrate(false).cleanDisabled(true).validateOnMigrate(true).load();
        if (args[0].equals("repair")) flyway.repair();
        else flyway.migrate();
        System.out.println("ISOLATED_FLYWAY_" + args[0].toUpperCase() + "_OK");
    }
}
