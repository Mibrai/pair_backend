-- Table audit_logs pour traçabilité RGPD
CREATE TABLE audit_logs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID REFERENCES users(id) ON DELETE SET NULL,
    action_type VARCHAR(50) NOT NULL,
    entity_type VARCHAR(50) NOT NULL,
    entity_id UUID,
    old_value TEXT,
    new_value TEXT,
    ip_address VARCHAR(45),
    user_agent VARCHAR(255),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Index pour recherche par utilisateur
CREATE INDEX idx_audit_logs_user_id ON audit_logs(user_id);

-- Index pour recherche par type d'action
CREATE INDEX idx_audit_logs_action_type ON audit_logs(action_type);

-- Index pour recherche par entité
CREATE INDEX idx_audit_logs_entity ON audit_logs(entity_type, entity_id);

-- Index pour recherche par date
CREATE INDEX idx_audit_logs_created_at ON audit_logs(created_at DESC);

COMMENT ON TABLE audit_logs IS 'Logs d''audit pour traçabilité RGPD (Articles 30, 32, 33)';
COMMENT ON COLUMN audit_logs.action_type IS 'Type action: CREATE, UPDATE, DELETE, EXPORT, LOGIN, LOGOUT, etc.';
COMMENT ON COLUMN audit_logs.entity_type IS 'Type entité: USER, PROGRAM, MESSAGE, etc.';
COMMENT ON COLUMN audit_logs.old_value IS 'Ancienne valeur (JSON)';
COMMENT ON COLUMN audit_logs.new_value IS 'Nouvelle valeur (JSON)';
