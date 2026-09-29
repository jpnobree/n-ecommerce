-- Extensões usadas pelo modelo de dados (PRD, seção 16): e-mail case-insensitive e busca textual.
CREATE EXTENSION IF NOT EXISTS citext;
CREATE EXTENSION IF NOT EXISTS pg_trgm;
CREATE EXTENSION IF NOT EXISTS unaccent;
