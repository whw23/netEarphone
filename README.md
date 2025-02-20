# netEarphone

一款能够将声音传送至服务端并进行播放的软件。目前支持浏览器网页/安卓APP。（摸鱼小工具）

1. 服务端：./NetEarphone （java spring boot + mvn）
2. 安卓端：./NetEarphoneAPK (kotlin + gradle)
3. nignx: ./nginx

## 技术栈

* Java spring boot (Server)
* WebSocket
* Kotlin (Android)
* 浏览器环回录制
* 安卓环回录制
* nginx反向代理
* docker

## 使用方式

1. 在服务端启动./NetEarphone (默认本机8080端口) `java -jar .\NetEarphone.jar`
2. http://localhost:8080 有一个页面  index.html 可以在本机测试。websocket接口为 ws://localhost:8080/ws
3. 由于非localhost网页浏览器没有权限获取音频，需要SSL证书加密http和ws
4. 通过 `ipconfig`/`ifconfig` 获取private ip，如果是服务器
   > 通过certbot申请免费SSL证书命令示例
   >
   > ```bash
   > sudo certbot certonly --standalone --preferred-challenges http --agree-tos --register-unsafely-without-email -d example.com
   > ```
   >
5. 如果没有域名，也可以通过内网穿透工具将本地服务映射到公网。（vscode里就有转发端口的工具）
6. 在./nginx中，提供了一个nginx配置文件，可以将http和ws代理到服务端。请修改配置文件中的域名和证书路径。并提供了一个docker-compose.yml文件，可以直接运行nginx容器。（`docker compose up -d`）
7. 安卓手机APK: ./NetEarphoneAPK/app/release/app-release.apk （目前在首次请求环路录制权限的时候会闪退，此时给出权限再次打开软件就可以了）
