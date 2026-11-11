NEXUS 完整功能版（暂不接支付）

包含：安卓/Capacitor 静态网页资产、登录/注册/邮箱验证/找回密码、账户资料、套餐展示、订单申请、订阅状态、管理员订单审核和手动开通订阅、Supabase RLS 数据库脚本。

重要：
1. 当前版本不接收付款、不自动扣款、不代表已经具备商业支付能力。
2. 第三方 Supabase 项目必须先运行 sql/01_nexus_schema.sql。你原有 profiles 表若已存在，脚本会补充其他表；请先备份数据库。
3. 管理员授权必须在 SQL 编辑器中手动执行脚本末尾的 INSERT，把 YOUR-USER-UUID 换成你自己的 Auth UID。绝不能把管理员权限开放给普通用户。
4. 本包把 Supabase Project URL 和 Publishable Key 放在前端。Publishable key 本来就是客户端公钥；绝对不要放 service_role/secret key。
5. 套餐的价格、时长和服务描述均为示例，正式经营前需要自行核实资质、合同、退款和隐私合规要求。
6. dist/index.html 已随包提供；外部 Supabase JS 通过 CDN 加载，首次使用需要网络连接。
7. Capacitor appId: com.nexus.app，appName: NEXUS，webDir: dist。

如果打包平台要求源码 ZIP，请上传本压缩包并选择 Android 调试安装包。不同第三方打包平台支持情况不同，不能保证它们都能直接识别此结构。
