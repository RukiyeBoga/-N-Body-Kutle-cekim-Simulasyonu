# SIMD N-Body Yerçekimi Simülatörü - Çalıştırma Rehberi

Bu proje, Aparapi kütüphanesi yardımıyla OpenCL üzerinden paralel GPU (SIMD) ve çok çekirdekli CPU (JTP) mimarilerini kullanarak N-Body yerçekimi simülasyonu gerçekleştirmektedir.

##  Sistem Gereksinimleri
- **Java JDK 8 veya daha yeni bir sürüm** (Sisteminizde Java 24 kurulu olduğu tespit edilmiştir).
- **Maven** (Proje içerisinde yerel Maven sürümü `tools/apache-maven-3.9.6` altında hazır olarak sunulmaktadır).
- Uyumlu bir ekran kartı ve güncel sürücüleri (GPU SIMD modundan tam verim alabilmek için).

---

## Projeyi Çalıştırma Yöntemleri

### Yöntem 1: Komut Satırından Çalıştırma (Tavsiye Edilen)
Proje klasörünün (`paralel_programlama_proje`) içinde bir terminal (PowerShell veya CMD) açın ve aşağıdaki komutu çalıştırın:

```powershell
.\tools\apache-maven-3.9.6\bin\mvn clean compile exec:java
```

> **Not:** Sisteminizde global Maven kurulu olmadığı için yukarıdaki komut proje içerisinde gömülü gelen yerel Maven'ı kullanır.

---

### Yöntem 2: IDE (IntelliJ IDEA / VS Code) ile Çalıştırma

#### IntelliJ IDEA ile:
1. IntelliJ IDEA'yı açın.
2. **File -> Open** adımlarını izleyerek `paralel_programlama_proje` klasörünü seçip açın.
3. Sağ altta Maven projesinin yüklenmesini ve bağımlılıkların indirilmesini bekleyin.
4. `src/main/java/com/nbody/NBodySimulator.java` dosyasını açın.
5. Sınıf veya `main` metodunun solundaki yeşil **Run** butonuna basarak projeyi başlatın.

#### VS Code ile:
1. VS Code'u açın.
2. **File -> Open Folder** adımlarını izleyerek `paralel_programlama_proje` klasörünü açın.
3. Java uzantılarının (Extension Pack for Java) yüklü olduğundan emin olun.
4. `src/main/java/com/nbody/NBodySimulator.java` dosyasını açıp kodun üstünde beliren **Run** butonuna tıklayın.

---

## Simülasyon Arayüzü ve Kontroller

Program açıldığında karşınıza görsel bir simülatör ekranı gelecektir:

- **Yürütme Modu (Execution Mode):** 
  - **GPU (SIMD):** Hesaplamaları ekran kartının paralel çekirdeklerinde çalıştırır.
  - **CPU (Çok Çekirdekli):** Java Thread Pool kullanarak çok çekirdekli CPU üzerinde çalıştırır.
  - **CPU (Sıralı):** Tek bir CPU çekirdeğinde ardışık olarak hesaplar (referans değerdir).
- **Hazır Senaryolar (Presets):** Galaksi Çarpışması, Güneş Sistemi ve Rastgele Bulut senaryoları arasında geçiş yapabilirsiniz.
- **Canlı Performans Paneli:** Seçilen modların çalışma sürelerini ve milisaniye bazında hızlanma oranlarını (Speedup) gösterir.
- **Kamera Kontrolleri:**
  - Sol tıklayıp sürükleyerek kamerayı döndürebilirsiniz.
  - Farenin tekerleğini (Scroll) kullanarak yakınlaşabilir / uzaklaşabilirsiniz (Zoom).

##YouTube Link
https://www.youtube.com/watch?v=VuXKrNPFZuI
