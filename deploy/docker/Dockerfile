# 블로그 앱(Spring Boot, app/backend + 화면 app/frontend) 컨테이너 이미지.
# 빌드: 저장소 루트에서  docker build -f deploy/docker/Dockerfile -t blog-app .
# 화면(React)을 먼저 빌드해 jar의 static/에 넣는다. 서버가 그 index.html에 머리말·첫 화면을 채워 보낸다.
# Gradle·Maven 어느 쪽이든 래퍼(gradlew/mvnw)가 있으면 그것으로 빌드한다.
# amd64·arm64(Oracle 무료 Ampere VM) 두 가지로 만든다. 화면·jar는 아키텍처와 무관해 빌드 단계는 러너 아키텍처($BUILDPLATFORM)에서 한 번만 돈다.

FROM --platform=$BUILDPLATFORM node:22-alpine AS web
WORKDIR /web
COPY app/frontend/package.json app/frontend/package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY app/frontend/ ./
RUN npm run build

FROM --platform=$BUILDPLATFORM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY app/backend/ ./
# 릴리스 노트(054): pom이 ../../CHANGELOG.md를 jar에 넣는다
COPY CHANGELOG.md /CHANGELOG.md
# 릴리스 노트 사진(093): pom이 ../../assets/releases/를 jar의 static/release-assets/에 넣는다
COPY assets/releases/ /assets/releases/
COPY --from=web /web/dist/ ./src/main/resources/static/
RUN set -eu; \
    if [ -x ./gradlew ]; then ./gradlew --no-daemon -q bootJar -x test && cp "$(ls build/libs/*.jar | grep -v plain | head -1)" /app.jar; \
    elif [ -x ./mvnw ]; then ./mvnw -q -B -DskipTests package && cp "$(ls target/*.jar | grep -v original | head -1)" /app.jar; \
    else echo "gradlew 또는 mvnw가 없습니다" >&2; exit 1; fi
# 계층 분리: 의존성 계층이 캐시되어 코드만 바뀌면 이미지 전송량이 작다
RUN mkdir /layers && cd /layers && java -Djarmode=tools -jar /app.jar extract --layers --launcher --destination .

FROM eclipse-temurin:21-jre
RUN groupadd --system --gid 10001 app && useradd --system --uid 10001 --gid app --no-create-home app
WORKDIR /app
COPY --from=build /layers/dependencies/ ./
COPY --from=build /layers/spring-boot-loader/ ./
COPY --from=build /layers/snapshot-dependencies/ ./
COPY --from=build /layers/application/ ./
USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
