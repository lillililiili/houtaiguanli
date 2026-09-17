# 证据直接预览接口与采集出处

适用日期：2026-09-16。此文描述代码契约，不代表已经部署或完成设备接入。

## 页面与操作

业务前台的证据台账在选中文件后直接展示内容；告警、处罚详情的证据卡直接打开同一预览组件，原来的类型弹窗与文件信息弹窗不再作为图片/录像的必经步骤。预览支持同组上一份、下一份、缩略图切换，以及返回原事项/台账。文件信息、关联、哈希与保管记录仍可展开查看。实时视频接入不属于本次修改。

## 内容接口

| 请求 | 权限 | 返回 |
| --- | --- | --- |
| `GET /api/v1/evidence-files/{id}` | `evidence:read` | 原元数据包装，增加下述可选采集出处字段 |
| `GET /api/v1/evidence-files/{id}/preview` | `evidence:preview` | 实际文件字节和校验后的 Content-Type |
| `GET /api/v1/evidence-files/{id}/thumbnail` | `evidence:preview` | 解码并缩小到最长边 320 px 的 PNG |
| `GET /api/v1/evidence-files/{id}/content` | `evidence:download` | 原件下载契约不变 |

预览与缩略图使用已有 Bearer 数据库会话，沿用文件组织/区域、目录启用和未关联文件可见性守卫，不依赖设备菜单权限。迁移只注册 `evidence:preview`，不自动向现有角色授予该权限；普通用户由管理员显式配置。开发测试超级管理员继续使用既有受环境限制的授权机制。

成功 JSON 文件返回原 JSON 字节，不是 `{ok,data,error}` 包装。失败仍用现有错误包装与正确 HTTP 状态。成功响应带 `Cache-Control: no-store, private`、`X-Content-Type-Options: nosniff` 和沙箱内容策略。没有公开文件地址、URL 会话令牌或新登录方案。

支持 PNG/JPEG/GIF（JDK 实际解码、格式相符、像素上限 4000 万）、WebP（RIFF/VP8 结构与尺寸校验）、PDF（头尾标记校验）、MP4（顶层 box 结构校验）、WebM（EBML头、文档类型、媒体段与轨道/帧簇标记校验）、UTF-8 纯文本及可解析 JSON。浏览器对 PDF、WebP和录像的实际解码仍可能失败，页面必须显示格式/编码不可显示信息，不能把存在元数据当作可播放。SVG、HTML、Office、未知类型不直接执行/嵌入。文本以转义正文显示。WebP 未新增解码依赖，缩略图返回不支持，页面显示预览卡，原图仍可预览。

服务端和客户端均限制单次预览 32 MiB。文件须 AVAILABLE、真实对象存在且当前字节大小/SHA-256与台账一致。每次读取不改变原件、哈希、保管状态或历史交接材料；发现新哈希不符本次拒绝，原台账状态的更新仍由原校验动作负责。冻结或留存到期不等于禁止读取。

| 状态/问题 | HTTP | code |
| --- | --- | --- |
| 未登录、权限不足、范围外 | 沿用身份/范围契约 | `UNAUTHENTICATED` / `FORBIDDEN` / `NOT_FOUND` |
| 入库中、缺失、损坏、已销毁 | 409 | `EVIDENCE_PENDING` / `EVIDENCE_MISSING` / `EVIDENCE_CORRUPT` / `EVIDENCE_DESTROYED` |
| 32 MiB 上限 | 413 | `EVIDENCE_PREVIEW_TOO_LARGE` |
| 格式/内容不支持 | 415 | `EVIDENCE_PREVIEW_UNSUPPORTED` |
| 存储/解码读取失败 | 409 | `EVIDENCE_PREVIEW_UNREADABLE` |

页面切换文件、关闭预览、导航离开或发现账号访问变化时中止请求、隔离迟到响应并回收 Blob URL。PDF只在严格PDF MIME分支使用浏览器内置阅读器；无原件下载权限时隐藏其下载/打印工具栏。已取得字节无法被服务端追溯收回；后续请求重新校验会话与权限。预览权限不代表能阻止已获准查看的用户自行保存内容。

## 审计

元数据读取继续记录 VIEW；预览为 PREVIEW；缩略图为 THUMBNAIL；原件下载继续为 DOWNLOAD，保留 DESTROY 等原动作。成功是内容读取已授权，不代表用户已阅读或视频播放完成。预览审计通过独立事务写入，状态/格式拒绝后抛错不会回滚拒绝日志。对象不可见或缺动作权限时只记录不包含对象详情的通用失败审计，不写泄漏存在性的对象访问记录。

## 入库时可登记的采集出处

`POST /api/v1/evidence-files` 在既有 multipart 字段上增加：

- `source_device_id`：可选，必须具有 `device:read`，设备在当前数据范围内且与证据组织/区域一致。名称由后端查设备档案并冻结为入库时的 `source_device_name`，不信任前端提供名称。
- `capture_longitude` / `capture_latitude`：可选、必须成对、WGS84，经度 [-180,180]、纬度 [-90,90]，拒绝非有限数字。

详情返回对应字段及 `capture_provenance=UPLOADER_DECLARED`，表示有权限的入库者登记的采集信息，不代表设备自动提供或平台独立核实。旧记录字段缺失仍显示“未记录”，不回填当前目标坐标或后来设备名称。采集出处纳入本次入库幂等请求指纹，只能在新文件 PENDING、version=0 时保存，不增加历史材料编辑接口。

## 验证入口

- `EvidencePreviewApiTest`：预览/下载分权、JSON原始字节、真实缩略图、范围隔离、伪MIME、当前哈希不符、拒绝审计、文件状态、撤权、WebP、坐标往返与来源设备快照。
- `EvidencePreviewPostgresTest`：继承HTTP场景，并在隔离 `advisory_verify_*` 数据库的随机schema验证全量迁移、从202609160002升级、重复迁移、审计动作保留、未默认授权普通角色；测试完成删除其临时schema。
- 业务前台真实浏览器验收需使用可解码、明确标记的测试文件，覆盖图片/PDF/录像/JSON和错误状态。旧1×1图片与缺失录像不能单独作为完整预览效果的验收依据。
