FROM ubuntu:22.04
RUN apt update && apt install -y openjdk-17-jdk-headless
COPY docker-run.sh /root/
RUN chmod 755 /root/docker-run.sh
COPY *.jar /root/
CMD ["/root/docker-run.sh"]