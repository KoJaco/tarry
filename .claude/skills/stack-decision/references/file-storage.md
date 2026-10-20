# File storage: R2 (default) vs S3

- **Default: Cloudflare R2.** Already set up and familiar; S3-compatible API; no egress fees (verify current terms); sits next to Workers.
- **Use S3 when** the app leans on other AWS services: Lambda/S3 event triggers, Textract/Transcribe/Bedrock/Rekognition pipelines, same-account IAM, Glacier/lifecycle tiers, or compliance tied to AWS.
- Use presigned URLs for browser uploads/downloads (both work with the AWS SDK; for R2 point the S3 client at the R2 endpoint). Scope: single key, short expiry, content-type and size limits.
- One bucket per environment; separate credentials per environment; agents get staging buckets only.
- Never serve user uploads from the app origin without validating type/size; scan if needed.
