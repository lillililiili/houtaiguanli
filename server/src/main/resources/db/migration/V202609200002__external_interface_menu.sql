-- Expose the existing interfaces permission as an admin menu; no role/action grant is added.
UPDATE app_permission SET route_key='interfaces', name='接口配置' WHERE permission_code='interfaces';
