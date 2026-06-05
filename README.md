# 🚀 Solenz Media Extractor API

Bu backend servisi, Android uygulamanızın YouTube, TikTok, Instagram gibi platformlardan sıfır depolama (**No-Storage**) politikasıyla anlık ses ve video linklerini (.mp4 ve .mp3 ham akışları) doğrudan çözebilmesi için geliştirilmiştir.

Uygulama arka planda en güncel ve stabil çözücü motor olan **yt-dlp**'yi direkt Python modülü olarak çalıştırır.

---

### 📦 Kurulum ve Yerel Çalıştırma

1. Python 3.10+ kurulu olduğundan emin olun.
2. Gerekli kütüphaneleri yükleyin:
   ```bash
   pip install -r requirements.txt
   ```
3. Sunucuyu başlatın:
   ```bash
   uvicorn main:app --reload --host 0.0.0.0 --port 8000
   ```
4. Tarayıcınızdan `http://localhost:8000/` adresine giderek kontrol edebilirsiniz.

---

### ☁️ Kolay Dağıtım (Deployment)

Bu proje **Dockerfile** ve **requirements.txt** ile hazır olarak verilmiştir. Herhangi bir kod değiştirmeden aşağıdaki adımlarla deploy edebilirsiniz:

#### 1. Railway ile Canlıya Alma (Önerilen)
1. GitHub hesabınızda yeni bir repository açıp `backend` klasöründeki dosyaları yükleyin.
2. [Railway.app](https://railway.app/) adresine gidin.
3. **New Project** -> **Deploy from GitHub repo** adımlarını izleyerek repo'nuzu seçin.
4. Railway otomatik olarak Dockerfile'ı algılayacak, ffmpeg kurulumunu yapacak ve size canlı bir API URL'si verecektir!

#### 2. Render.com ile Canlıya Alma
1. **Render'da** yeni bir **Web Service** oluşturun.
2. Repository bağlantınızı kurun.
3. Environment olarak **Docker** seçin (bu sayede ffmpeg otomatik yüklenir).
4. Sistemi yayına alın.

---

### 📡 API Kullanımı

#### **POST** `/api/extract`
* **JSON Gövdesi:**
  ```json
  {
    "url": "https://www.tiktok.com/@username/video/123456789"
  }
  ```
* **Başarılı API Cevabı (200 OK):**
  ```json
  {
    "success": true,
    "title": "Muazzam Video Başlığı",
    "thumbnail": "https://...",
    "duration": 42,
    "media": {
      "video_url": "https://...direct-stream-video-source...",
      "audio_url": "https://...direct-stream-audio-source..."
    }
  }
  ```
