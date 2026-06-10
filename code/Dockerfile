FROM python:3.12-slim
WORKDIR /app
COPY . .
RUN useradd --create-home --uid 10001 appuser
USER appuser
ENV PYTHONPATH=/app/src
ENV SHOPREC_HOST=0.0.0.0
ENV SHOPREC_PORT=8080
EXPOSE 8080
HEALTHCHECK --interval=10s --timeout=2s --start-period=5s --retries=3 \
  CMD ["python", "-c", "import urllib.request; urllib.request.urlopen('http://127.0.0.1:8080/health/ready', timeout=1)"]
CMD ["python", "-m", "shoprec.server"]
