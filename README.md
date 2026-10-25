# 🚗 CarMirror Auto — Sıfır Gecikmeli Android Auto Ekran Yansıtma

**CarMirror Auto**, Android telefonunuzun ekranını ve sesini **hiçbir sunucu, internet paketi veya ücretli servis kullanmadan**, doğrudan donanımsal **OpenGL ES 2.0 + Android Auto Projected Surface** hattı üzerinden araç ekranınıza **sıfır gecikmeyle** yansıtır.

## ✨ Özellikler
- **⚡ Sıfır Gecikme (Zero-Latency GPU Pipeline):** `MediaProjection` -> `VirtualDisplay` -> `GL_TEXTURE_EXTERNAL_OES` -> `EGL14` donanımsal grafik hattı ile CPU kopyalaması olmadan doğrudan araç ekranına 60 FPS akış.
- **🔌 Hem USB Kablo Hem Kablosuz Android Auto:** Arabanıza nasıl bağlanırsanız bağlanın otomatik çalışır.
- **🔊 Otomatik Ses Aktarımı:** Videoların, YouTube'un, müziğin ve tüm telefon seslerinin doğrudan araç hoparlörlerinden çıkmasını sağlar.
- **📐 Otomatik Yatay Mod (Auto-Landscape) & 4 Ölçekleme Modu:**
  - **Tam Doldur (Sıfır Boşluk):** Siyah kenarlık bırakmadan araç ekranının tamamını doldurur.
  - **Orantılı Sığdır:** Görüntü oranını koruyarak sığdırır.
  - **Yakınlaştır (Kırp):** Oranı bozmadan tam ekran yakınlaştırır.
  - **90° Yatay Çevir:** Dikey kilitli uygulamaları araç ekranında yatay gösterir.
- **🌙 Telefon Ekranını Karart (Cep / Pil Koruma Modu):** Araç ekranında görüntü %100 tam parlaklıkta devam ederken, telefonun fiziksel ekran arka ışığını minimuma (`0.001f`) indirir ve ekranın kilitlenip yayını kesmesini önler.
- **🌐 Yerel Wi-Fi Tarayıcı Yedeği:** İhtiyaç halinde internet harcamadan yerel Wi-Fi Hotspot üzerinden (`http://<telefon-ip>:8080`) tarayıcıdan da görüntülenebilir.

---

## 📲 Telefona Kurulum ve İlk Ayar (30 Saniye)

1. **[Son Sürüm (Releases) sayfasından `CarMirror-Auto.apk` dosyasını telefonunuza indirin ve kurun.](../../releases/latest/download/CarMirror-Auto.apk)**
2. Uygulamayı açıp **"Üstte Gösterme İzni Ver"** butonuna dokunarak otomatik yatay mod ve ekran karartma iznini açın.
3. Uygulama içindeki **"Android Auto Ayarlarını Aç"** butonuna dokunun:
   - En alttaki **Sürüm (Version)** yazısına arka arkaya **10 kez** dokunarak **Geliştirici Modu**'nu açın.
   - Sağ üstteki **üç nokta (⋮)** menüsünden **Geliştirici ayarları (Developer settings)**'na girin.
   - En alttaki **Bilinmeyen kaynaklar (Unknown sources)** kutucuğunu işaretleyin.
4. Telefonunuzda **CarMirror Auto** içinden **"EKRAN YANSITMAYI BAŞLAT"** butonuna basın ve ekran paylaşım onayını verin.
5. Telefonunuzu USB veya Kablosuz olarak arabanıza bağlayın ve araba ekranındaki Android Auto menüsünden **CarMirror Auto**'yu açın!
