-- Admin token: a secret token for MUTATIONS (adding/removing players). The view stays
-- public through the plain token in the link. New dashboards get a random adminToken
-- generated in the app; existing (legacy) ones get admin_token = token so that
-- an old link can still manage (GET returns adminToken only for legacy —
-- there it is not a secret, because it is already in the URL).
ALTER TABLE dashboard ADD COLUMN admin_token VARCHAR(36);

UPDATE dashboard SET admin_token = token WHERE admin_token IS NULL;

ALTER TABLE dashboard ALTER COLUMN admin_token SET NOT NULL;

CREATE UNIQUE INDEX uk_dashboard_admin_token ON dashboard (admin_token);
