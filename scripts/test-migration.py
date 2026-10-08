#!/usr/bin/env python3
"""Exercise the actual v1 -> v2 -> v3 migration SQL against populated SQLite data.
Room's on-device open/identity verification still requires an Android device.
"""
import pathlib
import re
import sqlite3

root = pathlib.Path(__file__).resolve().parents[1]
schema = root / 'android/app/src/test/resources/local/schema-v1.sql'
source = root / 'android/app/src/main/java/io/ather/pro/data/local/AtherDatabase.kt'
connection = sqlite3.connect(':memory:')
connection.executescript(schema.read_text())
connection.execute("INSERT INTO telemetry_history VALUES (1000, 0, 70.5, 'Ride')")
connection.execute("INSERT INTO trip_baseline VALUES (1, 2200.5, 70.5, 1000)")
connection.execute("INSERT INTO meta VALUES ('migrated', 'true')")
connection.execute("INSERT INTO trips VALUES ('saved-ride',1000,2000,12,8,250,20.8,2,2188.5,2200.5,NULL,0)")
tables = ('telemetry_history', 'trip_baseline', 'meta', 'trips')
rows = {table: connection.execute(f'SELECT * FROM {table}').fetchall() for table in tables}
columns = {table: [row[1] for row in connection.execute(f'PRAGMA table_info({table})')]
           for table in tables}
statements = re.findall(r'db\.execSQL\("([^\"]+)"\)', source.read_text())
assert statements, 'No migration found'
for sql in statements:
    connection.execute(sql)
for table in tables:
    original_columns = ','.join(f'`{name}`' for name in columns[table])
    assert connection.execute(f'SELECT {original_columns} FROM {table}').fetchall() == rows[table], table
route_columns = ('durationSeconds', 'averageSpeedKmh', 'topSpeedKmh', 'encodedPolyline', 'routeSpeeds')
schema_columns = {row[1]: row for row in connection.execute('PRAGMA table_info(trips)')}
assert all(schema_columns[name][3] == 0 for name in route_columns), 'New route columns must be nullable'
assert connection.execute('SELECT ' + ','.join(route_columns) + ' FROM trips').fetchone() == (None,) * 5
connection.execute("UPDATE trips SET durationSeconds=600, averageSpeedKmh=30, topSpeedKmh=45, encodedPolyline='route', routeSpeeds='20,30' WHERE id='saved-ride'")
assert connection.execute('SELECT ' + ','.join(route_columns) + ' FROM trips').fetchone() == (600, 30, 45, 'route', '20,30')
connection.execute('INSERT INTO ride_history VALUES (2000, 35, NULL, NULL)')
assert connection.execute('SELECT speedKmh, odometerKm, rangeKm FROM ride_history').fetchone() == (35, None, None)
print('PASS: v1 data survives v2/v3 upgrades; new nullable route fields and sparse ride readings work')
