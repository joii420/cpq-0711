# CPQ Quotation System

Configure, Price, Quote system for manufacturing/industrial components.

## Tech Stack

- **Backend:** Java 17 + Quarkus 3.23.3
- **Frontend:** React 18 + TypeScript + Ant Design 5.x
- **Database:** PostgreSQL 16
- **Build:** Maven (backend), Vite (frontend)

## Quick Start

### Prerequisites

- Java 17+
- Node.js 24+
- Docker & Docker Compose

### Start Database

docker-compose -f cpq-backend/docker-compose.yml up -d

### Start Backend (dev mode)

cd cpq-backend
./mvnw quarkus:dev

### Start Frontend (dev mode)

cd cpq-frontend
npm install
npm run dev

Backend API: http://localhost:8081/api/cpq/
Frontend: http://localhost:5174/
