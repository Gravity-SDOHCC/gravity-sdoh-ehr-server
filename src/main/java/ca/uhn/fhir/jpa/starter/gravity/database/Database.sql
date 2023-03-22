BEGIN TRANSACTION;

DROP TABLE IF EXISTS `Users`;

CREATE TABLE IF NOT EXISTS `Users` (
  "username" varchar PRIMARY KEY,
  "provider_id" varchar NOT NULL,
  "password" varchar NOT NULL,
  "refresh_token" varchar DEFAULT NULL,
  "timestamp" datetime DEFAULT CURRENT_TIMESTAMP
);

DROP TABLE IF EXISTS `Clients`;

CREATE TABLE IF NOT EXISTS `Clients` (
  "id" varchar PRIMARY KEY,
  "secret" varchar NOT NULL,
  "redirect" varchar NOT NULL UNIQUE,
  "refresh_token" varchar DEFAULT NULL,
  "timestamp" datetime DEFAULT CURRENT_TIMESTAMP
);

COMMIT;
