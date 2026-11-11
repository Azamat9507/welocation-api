# WeLocation API (위로케이션)

Backend for WeLocation, a guide to the places in Korea that make a good day out: cafés, beaches, lakes, rivers and
viewpoints. Spots come from the Korea Tourism Organization's TourAPI every night; people can review, like and
bookmark them, search around a point on the map, and see what was liked most this week.

This repository is the backend only. A Next.js web client, an Expo mobile app and an AI guide that answers only from
stored spots are planned next and do not exist yet.

## Stack

- Java 21, Spring Boot 4.0.9, Spring Data JPA (Hibernate 7), Spring Security with JWT (HS256)
- MySQL 8.4 with Flyway migrations, spatial index for radius search
- Redis 7.4 for the weekly ranking
- Tests on real MySQL and Redis with Testcontainers (no H2), JUnit 5, MockMvc
- springdoc-openapi for Swagger UI (off in the `prod` profile)
- Docker Compose for local and full-stack runs

## Running it

You need Docker Desktop and JDK 21.

```bash
cp .env.example .env            # optional for local runs; see the comments inside
docker compose up -d            # MySQL on 127.0.0.1:3306, Redis on 127.0.0.1:6380
./gradlew bootRun               # API on http://localhost:8080
```

Swagger UI is at http://localhost:8080/swagger-ui/index.html.

To run everything in containers with the `prod` profile (no Swagger). Compose fills in laptop passwords for anything
missing from `.env` except `JWT_SECRET`, so on a real server set every value in `.env`:

```bash
echo "JWT_SECRET=$(openssl rand -base64 32)" >> .env
docker compose --profile app up -d --build
```

The `prod` profile itself has no defaults. Before anything connects anywhere, it stops with the names of whatever is
missing or empty among `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`, `REDIS_HOST`, `REDIS_PORT`, `REDIS_PASSWORD` and
`JWT_SECRET`. `TOURAPI_KEY` is optional; without it the nightly sync does nothing.

## API

| Method | Path | Auth | What it does |
|---|---|---|---|
| GET | `/api/spots?region=&category=&tag=&page=&size=` | open | Filtered list, spots with a photo first, then newest; with tags, like count and rating |
| GET | `/api/spots/{id}` | open | Detail with images, tags, like count and rating |
| GET | `/api/spots/nearby?lat=&lng=&radiusKm=&category=&size=` | open | Nearest spots within a radius, with distance |
| GET | `/api/spots/ranking/weekly` | open | Top 10 by likes this week (Monday 00:00 KST onwards) |
| GET | `/api/spots/{id}/reviews` | open | Reviews, newest first |
| POST | `/api/spots/{id}/reviews` | token | One review per user per spot |
| PUT / DELETE | `/api/reviews/{id}` | token | Author edits; author or admin deletes |
| POST / DELETE | `/api/spots/{id}/like` | token | Idempotent like, returns the new count |
| POST / DELETE | `/api/spots/{id}/bookmark` | token | Idempotent bookmark |
| GET | `/api/me/bookmarks` | token | My bookmarks, newest first |
| POST | `/api/auth/signup`, `/api/auth/login` | open | Login is rate limited per email and per IP |
| GET | `/api/me` | token | Current user |
| POST | `/api/guide` | token | Ask the AI guide; answers only with spots from the database (see below) |

## What was measured

Every number below comes from a test in this repository, run on a laptop with MySQL and Redis in Testcontainers. They
compare approaches with each other; they are not production latency.

**Radius search.** 100,000 generated spots, centre Seoul City Hall. Run with
`BENCHMARK=true ./gradlew test --tests '*NearbyBenchmarkTest'`, report in `build/reports/nearby-benchmark.md`.

| Case | Full table scan | Spatial index, one pass | Widening circles (shipped) | Redis GEO |
|---|---|---|---|---|
| 1 km, any category, 20 results | 417 ms | 4.1 ms | 4.3 ms | 0.5 ms |
| 50 km, any category, 20 results | 564 ms | 400 ms | 3.7 ms | 2.1 ms |
| 50 km, rare category, 20 results | 409 ms | 257 ms | 259 ms | 0.4 ms |

The spatial index alone gets slow when a wide radius covers a dense city, because every spot in the bounding box gets a
distance computed. Searching 1, 3, 10 and 30 km circles first and stopping once the page is full brings that case
down to a few milliseconds and returns exactly the same spots in the same order (asserted in the test).

Redis GEO was faster in every case and returned the same ids. It is not used. Real data is about 3,700 spots, it
would be a second copy to keep in sync, and every filter combination would need its own key. If the rare-category
case becomes a problem as data grows, a GEO set per category is the first step.

**1,000 people liking the same spot at once.** `BENCHMARK=true ./gradlew test --tests '*LikeBenchmarkTest'`.

| Strategy | Final count (worst of 5 runs) | Failed requests (worst of 5) | Time (median of 5) |
|---|---|---|---|
| Read the count, then write count + 1 | 100 (900 likes lost) | 0 | 1.2 s |
| Atomic `UPDATE` after the `INSERT` | 127 | 873 deadlocks | 0.4 s |
| `SELECT ... FOR UPDATE` on the spot row first (shipped) | 1000 | 0 | 2.1 s |
| Redis set per spot | 1000 | 0 | 0.03 s |

The deadlock comes from InnoDB: inserting the like takes a shared lock on the spot row through the foreign key, and
the following `UPDATE` needs an exclusive one. Locking the spot row as the first statement avoids it. The count stays
in MySQL, in the same transaction as the like row. `LikeConcurrencyTest` checks the exact count with 1,000 threads on
every test run.

**N+1 queries.** Hibernate statistics in `QueryCountTest`, 20 rows per page: the reviews page went from 23 statements
to 3, and the spots page from 23 to 4. The weekly ranking page takes 3.

## Design notes

- **Weekly ranking.** A Redis sorted set per ISO week, updated after the like commits. A like that rolls back never
  counts. MySQL stays the source of truth. If Redis fails, the same list is counted in MySQL, with the same order for
  ties, and Redis is left alone for 30 seconds so a hung Redis doesn't slow every like down. The set is rebuilt from
  MySQL at startup, every hour, when the key is missing and after a missed update. `WeeklyRankingTest` pauses the
  Redis container to check all of this.
- **AI guide.** Gemini (`gemini-3.5-flash-lite`, fallback `gemini-3.1-flash-lite`) through Spring AI 2.0.
  - The model never states facts. It searches the database through one read-only tool and answers with spot ids
    and a short reason.
  - Only ids the search actually showed it are accepted. Names, addresses and ratings on each card come from the
    database.
  - A route's order and distances are computed in code: the shortest order of the stops, on the same sphere as
    MySQL.
  - "A café by the sea" is answered with real distances to beaches, because the data has no descriptions or tags.
  - At most 3 searches per question, counted in the tool itself, because the model can ask for several in one turn.
  - Every model call has a 10-second timeout. The whole question has 30 seconds to start its calls, the fallback
    model included.
  - No retries; a failed call goes to the fallback model.
  - Daily limits per user and overall, counted in Redis.
  - The question is never logged.
  - Without `GEMINI_API_KEY` the endpoint answers 503 and the rest of the API runs as usual.
  - `GuideApiTest` scripts the model, so no test calls Gemini.
  - A live run of 12 questions (2026-11-12, free tier) answered 11 within 2.4 to 9.4 s, all with real spots. The
    exception was one question where both models stopped responding, which got a 503 after 31.6 s. Questions in
    Korean, English and Uzbek were answered in the same language. A request to print the system prompt and a
    weather question got no prompt and no made-up facts.
- **TourAPI sync.** Runs nightly. Five categories, upsert by TourAPI content id, retries with backoff on I/O errors
  and 5xx. It never deletes. The service key never appears in logs or error messages. Tests use recorded responses
  and never call the real API.
- **Security.**
  - Everything is closed by default and the open endpoints are listed in one place.
  - BCrypt for passwords.
  - Login is rate limited per IP. The client IP is read from `X-Forwarded-For` only when the request comes from a
    trusted proxy (`TRUSTED_PROXIES`, loopback by default), so a client can't pick its own IP. `TrustedProxyTest`
    checks both cases on a real Tomcat.
  - Ownership checks for reviews use the database, not the token.
  - Error responses carry no stack traces or SQL.
  - The coordinates sent to `/nearby` are never stored or logged.
- **Schema.** Owned by Flyway. Hibernate only validates it.

## Tests

```bash
./gradlew test                                   # needs Docker running
./gradlew test --tests '*WeeklyRankingTest'
BENCHMARK=true ./gradlew test --tests '*BenchmarkTest'
```

Integration tests run against MySQL 8.4 and Redis 7.4 containers. Endpoints are tested for the happy path,
validation errors and missing resources where those apply; secured ones also without a token and, where ownership
matters, with another user's token.

## Not done yet

- Deployment (reverse proxy, HTTPS, CI).
- CORS for the web client.
- The web, mobile and AI parts listed at the top.
