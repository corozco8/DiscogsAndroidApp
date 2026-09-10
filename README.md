# Discogs Android Seller App

A streamlined Android application built specifically for high-volume Discogs marketplace sellers.

The project focuses on reducing the number of taps, screens, and repeated actions required to list records, manage inventory, review orders, and search a large Discogs store.

https://github.com/user-attachments/assets/c7271669-9a67-4523-bc36-de1990e27562

## Design Philosophy

Official Discogs app updates introduced multi-step UI flows that can require several taps, swipes, and screen transitions for common seller tasks.

For sellers managing hundreds or thousands of records, small workflow delays add up quickly.

The goal of this project is maximum seller efficiency.

- **Minimal Tap Architecture:** Reduce unnecessary navigation and repetitive actions.
- **Speed-First Listing:** Make grading, pricing, and publishing records as fast as possible.
- **Live Inventory Management:** Search and modify active marketplace listings directly from the app.
- **Functional UI:** Prioritize useful information and direct actions over unnecessary interface complexity.
- **Seller-Focused Tools:** Build features around workflows used by active Discogs marketplace sellers.

> **Project Status:** The core seller workflow is functional and the application remains under active development. An AI-powered inventory search system is currently being expanded with richer Discogs release metadata.

---

## Key Features

### Rapid Marketplace Listing

Create Discogs marketplace listings using streamlined dialogs for:

- Media condition
- Sleeve condition
- Price
- Listing comments
- Marketplace status
- Direct publishing to Discogs

The goal is to reduce the time required to move from scanning a record to creating an active marketplace listing.

### Live Inventory Management

Search and manage store inventory directly from the Android application.

Features include:

- Inventory search
- Listing images
- Price display
- Media and sleeve condition
- Price updates
- Listing management
- Direct Discogs API actions

### Order Tracking

View marketplace orders and quickly inspect:

- Payment status
- Buyer information
- Order contents
- Order details

### Buyer Evaluations

Open Discogs buyer feedback and ratings directly from the seller workflow without losing your place in the app.

---

## AI Inventory Search

The app now includes an experimental natural-language inventory search system.

Instead of manually configuring filters, the seller can ask questions such as:

```text
Show me all Beatles records over $20
```

```text
Show me all records under $3
```

```text
What are my five most expensive records?
```

The request is interpreted by an OpenAI model, which selects the appropriate inventory-search parameters and calls a local inventory search tool through the Model Context Protocol (MCP).

<img width="635" height="1235" alt="AI Inventory Search" src="https://github.com/user-attachments/assets/2f823d40-924e-4242-8b05-ef568d1eaaa7" />

### AI Search Architecture

```text
Jetpack Compose Android App
          |
          v
       Retrofit
          |
          v
     FastAPI Backend
          |
          v
    OpenAI Agent
          |
          v
 Model Context Protocol
          |
          v
 Discogs Inventory Tool
          |
          v
 Local Inventory Cache
          |
          v
 Structured Results
          |
          v
     Android UI
```

The backend maintains a local inventory cache so AI searches do not need to download the entire Discogs inventory for every request.

A second persistent metadata cache is being built to support more advanced queries involving:

- Genre
- Style
- Label
- Release year
- Format
- Catalog number

This will allow searches such as:

```text
What are my five most valuable Jazz records?
```

```text
Show me all Blue Note records in my inventory
```

```text
Show me Soul-Jazz records from the 1960s
```

The metadata index is refreshed in the background while the primary inventory remains immediately searchable.

---

## Backend Architecture

The project now contains both an Android frontend and a Python backend.

```text
DiscogsAndroidApp/
|
├── app/
|   └── Android / Jetpack Compose application
|
├── backend/
|   ├── main.py
|   ├── agent_service.py
|   ├── discogs_client.py
|   ├── discogs_mcp_server.py
|   ├── inventory_cache.py
|   └── release_metadata_cache.py
|
└── start_backend.ps1
```

### FastAPI

FastAPI provides a local API used by the Android application for AI-powered inventory operations.

### OpenAI Agent

The OpenAI agent converts natural-language seller requests into structured search parameters.

The model does not generate inventory records. It uses the MCP inventory tool to retrieve actual records from the seller's cached Discogs inventory.

### Model Context Protocol

The MCP server exposes inventory search functionality as a tool that the OpenAI agent can call.

This keeps natural-language interpretation separate from the inventory search logic.

### Persistent Inventory Cache

Discogs inventory data is downloaded and stored locally.

This provides:

- Fast searches
- Reduced Discogs API usage
- Fewer rate-limit issues
- Faster AI responses

Release metadata is stored separately and reused between application launches.

---

## Tech Stack

### Android

- **Language:** Kotlin
- **UI:** Jetpack Compose
- **Design System:** Material 3
- **Architecture:** MVVM
- **State Management:** StateFlow
- **Concurrency:** Kotlin Coroutines
- **Networking:** Retrofit 2
- **JSON:** Kotlinx Serialization
- **Images:** Coil
- **Barcode Scanning:** Google ML Kit / Google Code Scanner

### Backend

- **Language:** Python
- **API Framework:** FastAPI
- **ASGI Server:** Uvicorn
- **HTTP Client:** HTTPX
- **AI:** OpenAI API
- **Agent Framework:** OpenAI Agents SDK
- **Tool Protocol:** Model Context Protocol (MCP)
- **Validation:** Pydantic
- **Caching:** Persistent JSON inventory and release metadata caches

### APIs

- Discogs API
- OpenAI API

---

## Getting Started

### 1. Clone the repository

```bash
git clone https://github.com/corozco8/DiscogsAndroidApp.git
cd DiscogsAndroidApp
```

### 2. Open the Android project

Open the project in Android Studio.

### 3. Configure local Android properties

Create or update:

```text
local.properties
```

with your local configuration.

Secrets and local configuration files are excluded from Git.

### 4. Configure the backend

Create:

```text
backend/.env
```

Example:

```env
DISCOGS_TOKEN=your_discogs_token
DISCOGS_USERNAME=your_discogs_username
OPENAI_API_KEY=your_openai_api_key
```

Do not commit this file.

### 5. Create the Python environment

From the `backend` directory:

```powershell
python -m venv .venv
```

Activate it on Windows:

```powershell
.\.venv\Scripts\Activate.ps1
```

Install the required Python dependencies.

### 6. Start the backend

The project includes:

```text
start_backend.ps1
```

or the FastAPI server can be launched manually from the backend directory:

```powershell
uvicorn main:app --host 0.0.0.0 --port 8000 --reload
```

### 7. Run the Android application

Build and run the application from Android Studio using an Android emulator or physical Android device.

The Android emulator communicates with the local backend through:

```text
http://10.0.2.2:8000/
```

---

## Security

API credentials are intentionally excluded from source control.

The repository ignores files including:

```text
backend/.env
backend/.venv/
local.properties
inventory_cache.json
release_metadata_cache.json
app/build/
```

API keys should never be committed directly into Kotlin or Python source files.

---

## Current Development

Current work is focused on expanding the AI inventory system with richer Discogs metadata and additional seller-oriented search capabilities.

Planned improvements include:

- Genre-aware inventory search
- Style-aware inventory search
- Label search
- Release-year filtering
- Format filtering
- Catalog-number filtering
- Faster metadata synchronization
- Improved AI search status feedback
- Additional inventory analytics

---

## Disclaimer

This is an independent project built using the public Discogs API.

It is not an official Discogs application and is not affiliated with or endorsed by Discogs.
