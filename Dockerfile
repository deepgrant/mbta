FROM ubuntu:22.04
COPY *.jar /root/
COPY docker-run.sh /root/
RUN chmod 755 /root/docker-run.sh
RUN apt update && apt install -y openjdk-17-jdk-headless
