# PDF Multimodal Indexing

PDF uploads are indexed through two related paths:

1. Text is extracted per page, grouped into bounded chunks, embedded, and stored in Milvus.
2. Raster images are extracted with their PDF transformation coordinates, stored in MinIO,
   summarized by the configured DashScope vision model, embedded, and stored in Milvus.

Text and image records share `documentId`, `page`, and `sectionId`. Text metadata contains
`relatedImageIds`; image metadata contains `relatedChunkIds`. Search returns direct vector hits
first and then expands at most two related records per hit, with a hard maximum of eight results.
Expansion is one level only.

## Milvus metadata

Text records use `contentType=pdf_text` and include:

- `chunkId`, `chunkIndex`
- `page`, `pageCount`, `sectionId`
- normalized `boundingBox` with a top-left origin
- `relatedImageIds`
- `originalPdfObjectKey` when MinIO is available

Image records use `contentType=pdf_image` and include:

- `imageId`, `imageSha256`, pixel dimensions
- `page`, `pageCount`, `sectionId`
- normalized `boundingBox` with a top-left origin
- `relatedChunkIds`
- `minioBucket`, `minioObjectKey`, `originalPdfObjectKey`

## Failure behavior

MinIO and image-summary failures do not fail text indexing. Failed images are logged and can be
re-indexed by uploading the same PDF again. Text-indexing failures still fail the indexing run.

## Local configuration

The existing `vector-database.yml` already exposes MinIO on port `9000` and its console on `9001`.
Configure credentials and image processing in `.env`; see `.env.example` for all keys. Set
`PDF_IMAGE_PROCESSING_ENABLED=false` to disable extraction and vision-model calls.
