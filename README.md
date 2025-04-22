# Smart Excel-JSON Tool (Backend)

[![Java](https://img.shields.io/badge/Java-21-blue?logo=java)](https://www.oracle.com/java/)
[![Spring Boot](https://img.shields.io/badge/Spring_Boot-3.2.4-brightgreen?logo=spring-boot)](https://spring.io/projects/spring-boot)
[![Live](https://img.shields.io/badge/Live-smartexceljson.me-success?logo=vercel)](https://smartexceljson.me)
[![Dockerized](https://img.shields.io/badge/Docker-Supported-blue?logo=docker)](https://www.docker.com/)
[![License](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)
[![Status](https://img.shields.io/website?url=https%3A%2F%2Fsmartexceljson.me)](https://smartexceljson.me)

> A powerful Java + Spring Boot backend that allows users to seamlessly convert Excel ↔ JSON, and generate JSON Schemas — enhanced with Gemini AI.


> 🧠 AI-powered | ⚡ Async & Reactive | 💾 Caching | 🔐 Rate-limited

---

## ✅ Features

- 📥 **Excel ➝ JSON** conversion (multi-sheet support)
- 🧠 AI-enhanced JSON (via Google Gemini)
- 📤 **JSON ➝ Excel** conversion:
  - AI-modified cells highlighted
  - Tooltips for original values
  - Bottom legend explaining AI changes
- 📐 **AI-powered JSON Schema generation** from Excel structure
- ⚡ Reactive async processing (`Mono`)
- 💾 Caffeine caching for AI & full pipelines
- 📉 Rate limiting using Bucket4j
- 🌐 CORS enabled for frontend communication
- 🧩 Clean modular code: `raw`, `ai`, `facade` layers

---

## 📡 API Endpoints

| Method | Endpoint | Description |
|--------|----------|-------------|
| `POST` | `/excel-to-json` | Upload Excel file, get raw/AI-enhanced JSON |
| `POST` | `/json-to-excel` | Upload JSON file or body, get Excel (highlighted if AI used) |
| `POST` | `/generate-schema` | Upload Excel file to get AI-generated JSON schema |

---

## 🛠️ Tech Stack

- **Java 21**, **Spring Boot 3**
- **Spring WebFlux** (reactive)
- **Apache POI** (Excel parser/generator)
- **Google Gemini API** (AI enhancement)
- **Caffeine** (for caching)
- **Bucket4j** (rate limiting)
- IntelliJ IDEA, Postman

---

## ⚙️ Configuration

In `src/main/resources/application.properties.example`:

```properties
gemini.apikey=YOUR_GEMINI_API_KEY
gemini.model=gemini-2.0-flash
spring.servlet.multipart.max-file-size=10MB
spring.servlet.multipart.max-request-size=11MB
spring.mvc.async.request-timeout=120s
```

**👉 Don’t commit your real key!**  
Copy this file as `application.properties` and add it to `.gitignore`.

---

## 🧪 Testing

Use Postman or `curl` to hit these endpoints:

```bash
curl -X POST http://localhost:8080/excel-to-json \
  -F "file=@yourfile.xlsx" \
  -F "useAI=true"
```

---

## ▶️ Running Locally

```bash
# In project root
mvn clean install
mvn spring-boot:run
```

Or run `SmartExcelJsonToolApplication` via IntelliJ IDEA.

---

## 📂 Project Structure

```
📁 controller/
📁 service/
   ├── exceljson/
   ├── jsonexcel/
   └── schemageneration/
📁 cache/
📁 config/
📁 exception/
📁 util/
```

---

## 🐳 Docker Support (Optional)

You can run the backend in a container using Docker:

### 1. Build the project JAR:
```bash
mvn clean package
```

### 2. Build the Docker image:
```bash
docker build -t smart-excel-json-tool-backend .
```

### 3. Run the container:
```bash
docker run -p 8080:8080 smart-excel-json-tool-backend
```

The backend will be live at: `http://localhost:8080`



---


## 📘 License

MIT — [Suman Kumar](https://github.com/SumanKumar5)
