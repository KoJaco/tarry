# Environments (fill in; used by the `deployer` pre-flight)

| | dev | staging | production |
|---|---|---|---|
| AWS account id | | | |
| AWS region | | | |
| Lambda function / alias | | `...-staging` / `live` | `...` / `live` |
| Neon project | local/branch | `<app>-staging` | `<app>-prod` |
| Cloudflare Worker / env | `wrangler dev` | `--env staging` | `--env production` |
| R2 bucket (or S3) | | | |
| API URL / Web URL | | | |
| Secrets location | | | |

Rules: agents get **staging** credentials only. Production deploys: GitHub Actions → `production` Environment approval. Rollback: Lambda `live` alias → previous version; Workers → previous version; DB → expand/contract so code can roll back without schema rollback.

Cost guards: AWS Budgets + alert (+ optional budget action), Free Tier usage alerts, Cloudflare billing alerts, Neon autoscaling limit, CloudWatch retention 14 days.
