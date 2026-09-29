# Project structure

In Android Studio, expand `app > kotlin+java > com.example.discogsandroidapp`
(some versions show `java` instead of `kotlin+java`). The main source files are
grouped into these packages:

| Package | Contents |
| --- | --- |
| `ai` | AI inventory search, its API, models, and sync tracking |
| `dashboard` | Profile dashboard and ratings screens |
| `data` | Shared Discogs models, local database, and repository |
| `inventory` | Store inventory, listing creation, and editing |
| `network` | Discogs API client, backend client, and request pacing |
| `orders` | Order lists, details, messages, caching, and status updates |
| `pricing` | Live listing prices, price guides, caching, and verification |
| `releases` | Record details, release search, master versions, and shared navigation state |
| `statistics` | Seller statistics, analytics, and customer history |
| `ui.shared` | Reusable dialogs, keyboard handling, lifecycle helpers, and web views |
| `ui.theme` | Colors, typography, and theme |

`MainActivity.kt` and `SellerSyncWorker.kt` remain in the base package. Their
class names are used by Android and previously scheduled background work.

Kotlin tests mirror the feature packages under `app/src/test/java` and
`app/src/androidTest/java`. JavaScript pricing parser tests remain under
`app/src/test/js`.

This organization keeps the existing app ID, database schema, storage keys,
and behavior. The shared `ReleaseViewModel` still coordinates multiple screens;
splitting its responsibilities is a separate refactor.
