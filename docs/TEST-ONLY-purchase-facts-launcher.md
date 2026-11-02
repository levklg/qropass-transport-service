# TEST ONLY — локальный Transport Purchase Facts (QRP-TRANSPORT-E2E-001)

Не использовать с production PostgreSQL, банковскими сертификатами или ключами.
Новый gRPC listener запускается исключительно ручной командой, только на loopback.
Это отдельная JVM Transport, а не Core ↔ Transport E2E.

## Требования

Java 21, Maven, OpenSSL, Docker с PostgreSQL 15 и bash.
Создать отдельную PostgreSQL 15 с именем **qropass_transport_e2e_test**.
Пример: `docker run --rm --name qrp-transport-e2e-db -e POSTGRES_DB=qropass_transport_e2e_test -e POSTGRES_USER=qropass_test -e POSTGRES_PASSWORD=local_test_only -p 127.0.0.1:55439:5432 postgres:15`.
После эксперимента завершить `docker stop qrp-transport-e2e-db`.

## TEST ONLY PKI

Генерировать ключи во внешнем временном каталоге, например `mktemp -d`.
Для воспроизводимой генерации использовать openssl и отдельный CA, как уже
сделано в `PurchaseFactsMtlsPostgresTest.certificates()`.
Предметные сертификаты:
- Transport server URI SAN `spiffe://qropass.test/transport`, EKU `serverAuth`;
- Core client URI SAN `spiffe://qropass.test/core`, EKU `clientAuth`.
Сертификаты и приватные ключи не коммитить, не копировать из production.
В тестах TLS сервер сверяет клиентский SAN с `spiffe://qropass.test/core`.
Core должен проверять CA и серверный сертификат тестового Transport.

## Запуск отдельного JVM

Из корня репозитория (Linux/macOS/Git Bash):

```bash
mvn -q -DskipTests test-compile dependency:build-classpath -Dmdep.outputFile=target/test-classpath.txt
export QRP_TEST_ONLY_LAUNCH=I_ACKNOWLEDGE_TEST_ONLY
export QRP_TEST_DB_URL='jdbc:postgresql://127.0.0.1:55439/qropass_transport_e2e_test'
export QRP_TEST_DB_USER=qropass_test
export QRP_TEST_DB_PASSWORD=local_test_only
export QRP_TEST_CA=/tmp/qrp-pki/ca.crt
export QRP_TEST_SERVER_CERT=/tmp/qrp-pki/server.crt
export QRP_TEST_SERVER_KEY=/tmp/qrp-pki/server.key
export QRP_TEST_SEED=YES
export QRP_TEST_GRPC_PORT=0
java -cp "target/test-classes:target/classes:$(cat target/test-classpath.txt)" \
 com.qropass.transport.e2e.PurchaseFactsStandaloneLauncher
```

Установка `QRP_TEST_SEED=YES` **вставляет только TEST ONLY** организацию,
назначение, маршрут и опубликованную версию в изолированную БД; при повторном запуске
на той же БД селектор по ИНН может стать неоднозначным. Для повтора пересоздать контейнер.

После запуска лог содержит `TEST_ONLY_FIXTURE` с селекторами и
`TEST_ONLY_PURCHASE_FACTS_READY pid=... host=127.0.0.1 port=...`.
Порт `0` выбирает свободный порт; скопируйте выведенный порт в конфигурацию
Core test-only клиента. Не добавляйте этот порт в публичные прокси.

Для остановки нажать Ctrl+C, дождаться завершения JVM; затем
`docker stop qrp-transport-e2e-db` и удалить временную PKI.

Ожидаемый RPC:
`qropass.transport.purchasefacts.v1.TransportPurchaseFactsService/ResolveCityTransportContext`.
Другие сервисы в listener не регистрируются. Результат содержит
`city_category_confirmed=false` и `sale_permitted=false`.
