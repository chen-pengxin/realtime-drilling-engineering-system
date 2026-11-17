FROM maven:3.9-eclipse-temurin-17 AS document-reader
WORKDIR /build
COPY tools/legacy-office-reader ./
RUN mvn -q package

FROM python:3.11-slim
WORKDIR /opt/algorithm-server
RUN apt-get update && apt-get install -y --no-install-recommends default-jre-headless libreoffice-writer libreoffice-calc libreoffice-draw fonts-noto-cjk tesseract-ocr tesseract-ocr-eng tesseract-ocr-chi-sim && rm -rf /var/lib/apt/lists/*
COPY vendor ./vendor
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
COPY app ./app
COPY train_templates ./train_templates
COPY data ./data
COPY tools ./tools
COPY --from=document-reader /build/target/legacy-office-reader.jar ./tools/legacy-office-reader/target/legacy-office-reader.jar
RUN mkdir -p models
ENV PYTHONPATH=/opt/algorithm-server
EXPOSE 8000
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000"]
