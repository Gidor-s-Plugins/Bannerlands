-- Portable between SQLite and MySQL. Executed statement-by-statement on start-up.
CREATE TABLE IF NOT EXISTS towns (
  id BIGINT NOT NULL PRIMARY KEY,
  name VARCHAR(32) NOT NULL UNIQUE,
  description VARCHAR(256) NOT NULL DEFAULT '',
  promo VARCHAR(256) NOT NULL DEFAULT '',
  owner CHAR(36) NOT NULL,
  nation_id BIGINT NOT NULL DEFAULT 0,
  friendly_fire INT NOT NULL DEFAULT 0,
  perm_neutral INT NOT NULL DEFAULT 0,
  perm_trusted INT NOT NULL DEFAULT 15,
  perm_member INT NOT NULL DEFAULT 15,
  perm_ally INT NOT NULL DEFAULT 8,
  perm_enemy INT NOT NULL DEFAULT 0,
  created_at BIGINT NOT NULL,
  bonus_claims INT NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS town_members (
  town_id BIGINT NOT NULL,
  uuid CHAR(36) NOT NULL,
  role VARCHAR(16) NOT NULL,
  PRIMARY KEY (town_id, uuid)
);

CREATE TABLE IF NOT EXISTS town_trusted (
  town_id BIGINT NOT NULL,
  uuid CHAR(36) NOT NULL,
  PRIMARY KEY (town_id, uuid)
);

CREATE TABLE IF NOT EXISTS nations (
  id BIGINT NOT NULL PRIMARY KEY,
  name VARCHAR(32) NOT NULL UNIQUE,
  capital_town_id BIGINT NOT NULL,
  friendly_fire INT NOT NULL DEFAULT 0,
  created_at BIGINT NOT NULL
);

CREATE TABLE IF NOT EXISTS nation_relations (
  from_nation BIGINT NOT NULL,
  to_nation BIGINT NOT NULL,
  type VARCHAR(8) NOT NULL,
  PRIMARY KEY (from_nation, to_nation)
);

CREATE TABLE IF NOT EXISTS chunk_claims (
  world CHAR(36) NOT NULL,
  chunk_x INT NOT NULL,
  chunk_z INT NOT NULL,
  town_id BIGINT NOT NULL,
  claimed_at BIGINT NOT NULL,
  raided_until BIGINT NOT NULL DEFAULT 0,
  raider_nation BIGINT NOT NULL DEFAULT 0,
  last_siege BIGINT NOT NULL DEFAULT 0,
  PRIMARY KEY (world, chunk_x, chunk_z)
);

CREATE TABLE IF NOT EXISTS town_invites (
  id BIGINT NOT NULL PRIMARY KEY,
  nation_id BIGINT NOT NULL,
  town_id BIGINT NOT NULL,
  sender CHAR(36) NOT NULL,
  created_at BIGINT NOT NULL,
  delivered INT NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS player_profiles (
  uuid CHAR(36) NOT NULL PRIMARY KEY,
  name VARCHAR(16) NOT NULL,
  town_slots INT NOT NULL DEFAULT 1,
  last_seen BIGINT NOT NULL DEFAULT 0
);
