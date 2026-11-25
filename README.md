# MeteorBoss

Folia 26.2 için meteor etkinliği: gökten meteor düşer, "Cehennem Çukuru" açılır, içinden boss **Typhon** çıkar.
Paket (resource pack) gerekmez, boss vanilla blok display'lerinden oluşur.

## Komutlar (izin: `typhonboss`, varsayılan op; op olmayanlara bu izin verilirse kullanabilirler)
- `/meteor` : baktığın yönde 10 blok ileriye meteor düşürür (30 dk bekleme)
- `/meteor <x> <z> [dünya]` : belirli yere düşürür
- `/meteor etkinlik -z` : bekleme süresini yok sayarak başlatır
- `/meteor etkinlik -k` : etkinliği ve boss'u bitirir, çukur 5 sn'de kapanır
- `/meteor reload` : config'i yeniler
- `/meteor debug` : hata varsa düzeltmeyi dener, olmazsa `debug/` klasörüne rapor yazar

## Yayına alırken
1. Jar'ı `plugins/` içine at, ilk açılışta `config.yml` oluşur.
2. `boss.model-mode: blocks` olmalıdır (pack istenmediği için kurulmadı). Lag olursa `boss.model-quality: medium` ya da `low`.
3. Otomatik etkinlik için `auto-event.enabled: true` yap.
4. Önce `/meteor` ile bir kez dene, sonra duyur.
5. Sorun olursa `/meteor etkinlik -k`.

## Boss kuralları
- Blok kırmaz, çukur 5 dakikada kendi kapanır (yedek dosyasından, sunucu kapansa da geri kurar).
- Süper saldırı "Boşluk Patlaması": 5 sn hazırlanır, bu sırada kimse vuramaz, sonra herkesi savurur. Aralık 5 dk.
- Can: temel + oyuncu başına ek. Hasar: yakındaki oyuncu sayısına göre hafif artar (üst sınırlı).
- Kanat ve kuyruk görsel, hitbox gövde ve kafadır.

## Geliştiriciler ve kaynaklar
- Zyven, geliştirici, taban ve teknik oluşturucu. Altyapı ve ilerleyişi hazırladı.
- Claude, geliştirici, araştırma ve hataları onardı (fix). Açıklamalar ve diyer ayrıntılar.
- Bu mod `avenormc`'ye özel olarak tasarlanmış ve geliştirilmiştir.

- ip: **oyna.avenormc.com** / site: **avenormc.com**
