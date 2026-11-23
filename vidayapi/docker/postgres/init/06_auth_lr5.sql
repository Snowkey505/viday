-- =============================================================================
-- ЛР5: аутентификация повышенной надёжности.
--
-- Добавляет:
--   * viday.user_auth       — email/2FA, счётчик неудачных попыток, блокировка,
--                             дата последней смены пароля (плановая ротация);
--   * viday.auth_challenge  — одноразовые коды (2FA и восстановление доступа);
--   * viday.auth_email_outbox — «почтовый транспорт» для стенда/тестов:
--                             приложение кладёт письмо с кодом в outbox, E2E-тест
--                             читает его оттуда. Для production этот адаптер
--                             заменяется на SMTP (см. docs/lr5.md);
--   * роль viday_auth       — минимальные права для анонимных auth-эндпоинтов,
--                             чтобы не выдавать им viday_admin.
--
-- Пароли/коды в открытом виде в БД не хранятся: код подтверждения хранится
-- только как BCrypt-хеш. Открытый код есть лишь в письме (outbox) — это аналог
-- почтового ящика пользователя, а не хранилище учётных данных.
-- =============================================================================
SET search_path TO viday, public;

CREATE TABLE IF NOT EXISTS viday.user_auth (
    user_id INTEGER PRIMARY KEY,
    email VARCHAR(255) NOT NULL,
    two_factor_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    failed_login_attempts INTEGER NOT NULL DEFAULT 0,
    locked_until TIMESTAMP WITH TIME ZONE,
    password_changed_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_user_auth_user FOREIGN KEY (user_id)
        REFERENCES viday."user"(id) ON DELETE CASCADE,
    CONSTRAINT chk_user_auth_attempts CHECK (failed_login_attempts >= 0),
    CONSTRAINT chk_user_auth_email CHECK (position('@' IN email) > 1)
);

-- Email должен быть уникальным без учёта регистра: иначе восстановление
-- доступа по email станет неоднозначным.
CREATE UNIQUE INDEX IF NOT EXISTS uk_user_auth_email_lower
    ON viday.user_auth (lower(email));

CREATE TABLE IF NOT EXISTS viday.auth_challenge (
    id UUID PRIMARY KEY,
    user_id INTEGER NOT NULL,
    purpose VARCHAR(32) NOT NULL,
    code_hash VARCHAR(255) NOT NULL,
    attempts INTEGER NOT NULL DEFAULT 0,
    max_attempts INTEGER NOT NULL DEFAULT 5,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    consumed_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_auth_challenge_user FOREIGN KEY (user_id)
        REFERENCES viday."user"(id) ON DELETE CASCADE,
    CONSTRAINT chk_auth_challenge_purpose
        CHECK (purpose IN ('LOGIN_2FA', 'PASSWORD_RECOVERY')),
    CONSTRAINT chk_auth_challenge_attempts CHECK (attempts >= 0),
    CONSTRAINT chk_auth_challenge_max_attempts CHECK (max_attempts > 0)
);

CREATE INDEX IF NOT EXISTS idx_auth_challenge_user_purpose
    ON viday.auth_challenge (user_id, purpose, created_at DESC);

CREATE TABLE IF NOT EXISTS viday.auth_email_outbox (
    id BIGSERIAL PRIMARY KEY,
    challenge_id UUID NOT NULL,
    recipient VARCHAR(255) NOT NULL,
    subject VARCHAR(255) NOT NULL,
    body TEXT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_auth_email_outbox_challenge FOREIGN KEY (challenge_id)
        REFERENCES viday.auth_challenge(id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_auth_email_outbox_recipient
    ON viday.auth_email_outbox (recipient, id DESC);

-- ---------------------------------------------------------------------------
-- PostgreSQL-роль для auth-эндпоинтов: только то, что нужно логину/2FA/
-- восстановлению/смене пароля. Анонимный запрос не получает viday_admin.
-- ---------------------------------------------------------------------------
DO $$ BEGIN
    CREATE ROLE viday_auth;
EXCEPTION WHEN duplicate_object THEN
    RAISE NOTICE 'Role viday_auth already exists';
END $$;

GRANT CONNECT ON DATABASE viday TO viday_auth;
GRANT USAGE ON SCHEMA viday TO viday_auth;

GRANT SELECT, INSERT, UPDATE, DELETE ON viday."user" TO viday_auth;
GRANT SELECT ON viday.role TO viday_auth;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA viday TO viday_auth;

GRANT SELECT, INSERT, UPDATE, DELETE ON viday.user_auth TO viday_auth;
GRANT SELECT, INSERT, UPDATE, DELETE ON viday.auth_challenge TO viday_auth;
GRANT SELECT, INSERT ON viday.auth_email_outbox TO viday_auth;

-- Приложение (viday_app_user) и администратор стенда (viday_admin_user)
-- должны иметь возможность выполнить SET ROLE viday_auth.
GRANT viday_auth TO viday_app_user;
GRANT viday_auth TO viday_admin;

-- На случай прямого подключения тестового стенда не только через admin-роль.
GRANT SELECT, INSERT, UPDATE, DELETE ON viday.user_auth TO viday_admin;
GRANT SELECT, INSERT, UPDATE, DELETE ON viday.auth_challenge TO viday_admin;
GRANT SELECT, INSERT ON viday.auth_email_outbox TO viday_admin;
GRANT USAGE, SELECT ON SEQUENCE viday.auth_email_outbox_id_seq TO viday_admin;
