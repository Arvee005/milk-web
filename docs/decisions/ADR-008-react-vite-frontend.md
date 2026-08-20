# ADR-008: React + TypeScript + Vite Frontend

## Decision
Use React with TypeScript and Vite for the frontend.

## Status
Accepted (implemented)

## Context
Frontend needs to be modern, maintainable, and ready for future mobile/UX work.

## Decision
Adopt React for component-driven UI, TypeScript for type safety, and Vite for a fast developer experience.

## Why We Chose It
- React ecosystem is mature
- TypeScript reduces runtime errors
- Vite offers fast HMR and modern bundling

## Alternatives Considered
- Next.js or CRA; Vite+React is simpler and faster to iterate locally

## Advantages
- Fast iteration, type safety, good ecosystem

## Disadvantages / Trade-offs
- No server-side rendering by default (not required initially)

## Interview Takeaway
This stack prioritizes developer velocity and type safety for a modern web app.
