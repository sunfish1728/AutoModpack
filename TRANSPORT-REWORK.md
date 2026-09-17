# AutoModpack 連線重構版 1

適用 Minecraft 1.20.1 Forge。相容測試的 zstdnet 版本為本機安裝的 1.4.8。

## 這次改了什麼

- DownloadRoutes 在登入時一次解析下載路徑，區分遊戲公開入口、臨時本機代理與獨立下載服務。
- DownloadRoute 保存入口、TCP/ZSTD 傳輸模式和 TLS/AMMH/Minecraft 握手方式。清單、下載池、重試與啟動更新共用同一路徑；舊設定可直接讀取。
- DownloadTransport 統一管理連線建立、握手、TLS 與錯誤時的資源釋放。一般下載器不再直接判斷 zstdnet。
- zstdnet RAW 模式也會保留公開入口。明確指定下載主機或埠時，按独立 TCP 服務處理，不沿用遊戲壓縮。
- 先同步捕捉登入代理，然後背景處理憑證和下載，避免阻塞 Minecraft 網路執行緒。
- ZSTD 串流不再回頭關閉其 Socket，避免重入關閉和原生記憶體重複釋放。
- 共用協定可接收分段封包、跨壓縮區塊的刷新請求與空刷新清單；失敗的連線會清理，不再放回池中使用。
- 打包先重建載入器，避免只有內層遊戲模組更新、外層核心仍是舊版。保留上一版中文憑證輸入改善，這次確認輔助類別確實被打包。

## 已驗證

- 核心測試 57 項：0 失敗，0 略過。
- 成品 JAR 測試 25 項：0 失敗，0 略過。使用 Netty 4.1.82.Final / Gson 2.10，測試類別以成品 JAR 作為核心程式來源。
- 一般共用遊戲埠、獨立 TLS 下載埠、透明 TCP 轉發、PROXY v1、PROXY v2：取得清單、憑證確認、多連線下載、刷新、保存及讀回路徑後重連。
- 使用實際 zstdnet 1.4.8 ServerProxyRuntime：短握手、轉發 IP、取得清單、並行檔案下載、刷新、關閉後重連。
- 同一 Java 程序重複開關 100 次 ZSTD 串流／Socket。
- IPv6 scope 位址和埠的設定保存；RAW 模式入口保存；獨立下載埠優先規則；舊設定遷移。
- 修復前可重現：RAW 代理入口遺失、空刷新斷線、連續壓縮測試原生 heap corruption，以及 fix3 缺少 CertificateInput。重構版通過對應檢查。

## 邊界

測試中的檔案服務使用臨時資料夾，隔離伺服器關閉了玩家授權檢查；正式程式仍保留原有授權和 TLS 憑證確認。
沒有重新啟動你的完整模組包或遠端伺服器，因此尚未驗證整套遊戲登入、玩家白名單／憑證畫面操作、真實外網 FRP 部署及不同 zstdnet 版本。
透明 TCP 與 PROXY 測試驗證的是其傳輸行為，不是直接執行 FRP 程序。HTTP/WebSocket 代理、BungeeCord/Velocity 的遊戲協定代理不等同透明 TCP；這些需要另外安排可達的 AutoModpack 下載入口。
zstdnet 1.4.8 沒有公開的路徑查詢介面，目前私有欄位讀取集中在 ZstdNetCompatibility；該版本的握手偵測相容處理集中在 ZstdSocket。沒有宣稱支援所有未測試版本。

## 使用

伺服器與客戶端都使用 automodpack-mc1.20.1-forge-4.0.6-transport-rework1.jar。不要同時啟用舊版 AutoModpack。
本機 DESTOP 和 Prism 測試實例會換為新版；其他伺服器也需要在服務端替換此 JAR 並重啟。
憑證仍需自行確認；沒有自動略過驗證或預先寫入信任紀錄。

## 重建與檢查

Gradle 啟動使用 Java 21，Java 程式目標仍為 17。

```powershell
.\gradlew.bat --no-configure-on-demand :1.20.1-forge:build
python scripts/verify_release.py merged/automodpack-mc1.20.1-forge-4.0.6.jar
```

完整代理測試需傳入 -PzstdnetJar=<zstdnet JAR 絕對路徑> 與 -PzstdnetLoggingJar=<Mojang logging JAR 絕對路徑>。
成品測試使用 :core:packagedTest -PpackagedJar=<成品 JAR 絕對路徑> -PtestNettyVersion=4.1.82.Final，加上前述兩個代理測試參數。

SHA-256: 697eb39df1b732e740f588b53c927e8a212eed13d2982bd22c0bbc45d5f7c351
