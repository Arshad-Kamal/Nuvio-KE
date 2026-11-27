# MDBList TVDB map replies (Spec 105 S6 test fixtures)

Real public replies, fetched 2026-11-20 (Spec 105 smokes 1-2), unedited:

- `tvdb-map-1429.json`: `GET /shows/1429/tvdb-map/` (Attack on Titan): 116 links, S4E29-30 -> TMDb S0E36-37, 15 `tvdb_only`.
- `tvdb-map-30983.json`: `GET /shows/30983/tvdb-map/` (Detective Conan): 1,252 links, 6 merged TMDb episodes, 53 `tvdb_only`.
- `tvdb-map-404.json`: the 404 body (Breaking Bad, 1396: numbering matches TMDb).
- `imdb-show-tt0131179.json`: `GET /imdb/show/tt0131179/` (Detective Conan media info; `ids.tmdb` 30983).
