FROM python:3.12-slim
WORKDIR /app
# The Legacy service only needs its Python sources and runtime experiment
# configuration. Keep course material, tests, local databases, and credentials
# out of the final image even if the build context grows over time.
COPY src ./src
COPY config ./config
RUN useradd --create-home --uid 10001 appuser
USER appuser
ENV PYTHONPATH=/app/src
ENV SHOPREC_HOST=0.0.0.0
ENV SHOPREC_PORT=8080
EXPOSE 8080
HEALTHCHECK --interval=10s --timeout=2s --start-period=5s --retries=3 \
  CMD ["python", "-c", "import urllib.request; urllib.request.urlopen('http://127.0.0.1:8080/health/ready', timeout=1)"]
CMD ["python", "-m", "shoprec.server"]
