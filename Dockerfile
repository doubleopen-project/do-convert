FROM eclipse-temurin:11-jdk-focal AS build

COPY . /usr/local/src/do-convert

WORKDIR /usr/local/src/do-convert

RUN --mount=type=cache,target=/tmp/.gradle/ \
    export GRADLE_USER_HOME=/tmp/.gradle/ && \
    ./gradlew --no-daemon --stacktrace distTar

FROM eclipse-temurin:11-jre-focal AS run

COPY --from=build /usr/local/src/do-convert/build/distributions/do-convert-*.tar /opt/do-convert.tar

RUN mkdir -p /opt/do-convert

RUN tar xf /opt/do-convert.tar -C /opt/do-convert --exclude="*.bat" --strip-components 1 && \
    rm /opt/do-convert.tar && \
    /opt/do-convert/bin/do-convert --help

ENTRYPOINT ["/opt/do-convert/bin/do-convert"]