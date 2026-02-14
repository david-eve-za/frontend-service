# Product Definition: Frontend Service

## Initial Concept
This project is a monolithic application with a Spring Boot (Java) backend and an Angular (TypeScript) frontend. It appears to be a "frontend-service" that integrates with AI models (Google GenAI and Ollama) for functionalities possibly involving text processing, translation, or content generation, with data persistence handled by H2 Database.

## Primary Purpose
The primary purpose of this "frontend-service" is to act as an API Gateway & Orchestration layer, providing a unified access point for various backend services and coordinating their data and logic.

## Key Features
- **Data Processing & Transformation:** The service is expected to process and transform data received from various sources before presenting it to the user or passing it to other services. This includes handling diverse data formats and business logic for data manipulation.

## Technology Stack
- **Backend:** Spring Boot (Java 21) with Spring AI for integrating AI models (Google GenAI, Ollama), Spring Security, Spring Data JPA, and H2 Database for data persistence. OpenAPI (springdoc) is used for API documentation.
- **Frontend:** Angular (v21) with TypeScript, utilizing PrimeNG, PrimeIcons, Tailwind CSS, and PrimeUIX themes for a rich and responsive user interface.
- **Build Tools:** Maven for backend build automation and npm (managed via `frontend-maven-plugin`) for frontend dependency management and build processes.

## Architecture
The project follows a Monolithic architecture, where both the frontend (Angular) and backend (Spring Boot) components are developed and deployed as a single, cohesive unit. This approach simplifies initial development and deployment, with the `frontend-maven-plugin` facilitating the integrated build process.
