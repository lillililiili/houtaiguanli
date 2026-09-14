-- 撤回阶段 9 把 airspace / risk 提升为一级菜单的改动（用户 2026-09-07 裁定）。
--
-- 背景：这两个权限码自 V202609030001 起就在目录里，但它们是照原型 Demo 的导航反推出来的，
-- 并非任何需求、会议纪要或技术规范要求的模块，且原型里它们只是"飞行计划页的别名"（route_key 为空，
-- 点击落到 flights）。V202609050060 给它们补了 route_key，于是侧边栏多出两个一级菜单。
-- 用户确认原先页面里没有这两个模块，故恢复为别名：只清 route_key，菜单即消失。
--
-- 不动的部分：阶段 9 的 ACTION 权限（airspace:manage、airport:read/manage、risk:evaluate、
-- flight:authorize）与其后端接口全部保留——能力已经建好，只是暂不在导航里暴露；
-- 任何角色的 menu_enabled 也不改，避免影响管理员已做过的授权。
UPDATE app_permission SET route_key = NULL, name = '空域与航线' WHERE permission_code = 'airspace';
UPDATE app_permission SET route_key = NULL, name = '空间安全风险' WHERE permission_code = 'risk';
