# WeLocation (위로케이션)

"위로가 되는 장소": a guide to Korea's most beautiful cafés, seaside and lakeside spots and fun places, on web and mobile.

## Stack

- Backend: Java 21, Spring Boot 4, Spring Data JPA, MySQL 8.4, Redis, Spring Security + JWT
- Web: Next.js
- Mobile: React Native (Expo)
- Data: Korea Tourism Organization TourAPI (data.go.kr), Kakao Map
- AI: Spring AI (answers only from places stored in the database)

## Phase 1: backend (first)

Domain
- Spot: name, category (CAFE, ATTRACTION, BEACH, LAKE, RIVER, VIEWPOINT), address, region, latitude/longitude, images, tags (ocean view, rooftop, pet friendly), source (TOURAPI or USER)
- User, Review, Bookmark, Like

API
- `GET /api/spots` filter by region, category, tag, with paging
- `GET /api/spots/nearby?lat=&lng=&radiusKm=` spots within a radius
- `GET /api/spots/{id}` detail with images and review summary
- `POST/GET /api/spots/{id}/reviews`
- `POST/DELETE /api/spots/{id}/bookmark`, `POST/DELETE /api/spots/{id}/like`
- `GET /api/spots/ranking/weekly` top 10 this week
- `POST /api/auth/signup`, `POST /api/auth/login`

Infrastructure
- Nightly TourAPI sync (scheduler), upsert by TourAPI content id
- Docker Compose (app, MySQL, Redis), Swagger UI
- Tests on real MySQL and Redis with Testcontainers

Topics this phase covers
- Radius search: MySQL spatial index vs Redis GEO, measured
- Weekly ranking: Redis sorted set
- Like counter under concurrent requests: no lock → DB lock → Redis, proven by a test with 1,000 threads
- JPA N+1 on spot → images → reviews, fixed with fetch join / batch size, query count before and after
- External API sync: retries, timeouts, idempotent upsert

## Phase 2: web (Next.js)

Map with Kakao Map, list and filters, spot page, bookmarks, login.

## Phase 3: AI guide

"A quiet café by the sea in Busan" → answers only with spots from the database, can plan a 3-stop route. Prices, addresses and opening hours come from data, never from the model. Fallback model and a per-user rate limit.

## Phase 4: mobile (Expo)

Same API: map, nearby spots, bookmarks.

## Needed before Phase 1 sync works

- TourAPI service key from data.go.kr (한국관광공사_국문 관광정보 서비스)
- Kakao Developers app key (Phase 2)
