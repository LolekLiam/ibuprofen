# Synthetic test resources

These fixtures are original, hand-authored test data, not captured eAsistent responses. All schools, people, class IDs, rooms, assessments and credentials are invented. API field names and HTML selectors are retained only to exercise the parsers. The JWT-shaped login token has a dummy signature and cannot authenticate to a service.

The HTML covers metadata selectors, five dates, nested lesson tables, visible/hidden duplicates, split groups, cancellation markers and empty periods. The JSON covers login/profile parsing, week selection, ordinary and replacement lessons, cancellations, all-day events, notes and grade summaries. Original synthetic Plus gradebook and notification-history fixtures also cover semesters, object/scalar final grades, retakes, nonnumeric grades, pagination, duplicates and school-year filtering. Tests run offline.

The fixture files in this directory and its subdirectories are licensed under [MIT](LICENSE), allowing reuse by the AGPL-3.0 app and the MIT API. This exception applies only to test resources, not to the app source code. The API maintains its own copies in `ibuprofen-api/tests/fixtures/`.
