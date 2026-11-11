NEXUS 更新包说明

本包按用户选择的 A 方案调整：登录后首先显示连接主页；账户总览、套餐、订单、个人资料和管理后台保留在下方的个人中心/功能导航中。

原有 N. 开屏动画 CSS 与时序保持不变。

重要：当前连接按钮、地区与节点列表仍是前端交互原型，不会建立真实 VPN 隧道。节点延迟不显示伪造数值。真实连接必须后续接入合法授权的服务端及客户端网络实现。

三个网页入口已同步：
- index.html
- dist/index.html
- app/src/main/assets/public/index.html

替换方式：将本包内文件上传并覆盖 GitHub 仓库根目录对应文件；不要把整个 NEXUS-main 子目录再次套进仓库。上传后等待 GitHub Actions 构建完成，再下载最新的 NEXUS-debug-apk Artifact。
