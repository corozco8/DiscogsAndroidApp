# Discogs Android Seller App

A lightweight, streamlined Android application built specifically for high-volume Discogs marketplace sellers. 

https://github.com/user-attachments/assets/c7271669-9a67-4523-bc36-de1990e27562

## Design Philosophy & Purpose
Official Discogs app updates (v3.0+) introduced multi-step UI flows that require excessive taps, swipes, and screen transitions to perform basic tasks. For active vinyl sellers, every extra tap adds up, slowing down listing rates, causing user frustration, and ultimately costing both sellers and Discogs money in potential transaction volume.

**The core goal of this app is maximum efficiency:**
- **Minimal Tap Architecture:** Redesigned workflows engineered to require the fewest possible taps, swipes, and navigation hops to complete key selling tasks.
- **Speed-First Inventory & Listing:** Built specifically to help sellers list more records per hour with minimal friction.
- **Functional & Direct:** Eliminates unnecessary UI clutter in favor of quick inputs, instant state updates, and direct API actions.

*Project Status:* Approximately **90% of the core vision** has been realized within the parameters and rate limits of the public Discogs API. The app remains an active work in progress.

---

## Key Features
- **Rapid Marketplace Listing:** Streamlined dialogs with media/sleeve grading, live price suggestions, and direct single-tap publishing (`For Sale`).
- **Live Inventory Management:** Quick-search your store inventory, update prices on the fly, and remove sold or unlisted inventory instantly.
- **Order Tracking:** Track open orders by payment status with instant feedback and order detail views.
- **Buyer Evaluations:** Native web integration to inspect user feedback without losing place in your workflow.

---

## Coming Soon 
- **AI Inventory Search***
<img width="501" height="741" alt="image" src="https://github.com/user-attachments/assets/cacc84ea-8715-461e-96f2-c33fbb248b42" />


## Tech Stack
- **Language:** Kotlin
- **UI Framework:** Jetpack Compose (Material 3)
- **Architecture:** MVVM (Model-View-ViewModel) with Kotlin Coroutines & StateFlow
- **Networking:** Retrofit 2 & Gson / Serialization

---

## Getting Started
1. Clone the repository:
   ```bash
   git clone [https://github.com/corozco8/DiscogsAndroidApp.git](https://github.com/corozco8/DiscogsAndroidApp.git)
2. Open the project in Android Studio.
3. Build and run on an Android device or emulator (API 26+).
