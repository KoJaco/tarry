// Lambda entrypoint. Function URLs use the same payload shape as API Gateway HTTP API (v2).
// If the adapter ever mismatches your Function URL payload, switch to AWS Lambda Web Adapter instead.
package main

import (
	"context"

	"github.com/aws/aws-lambda-go/events"
	"github.com/aws/aws-lambda-go/lambda"
	"github.com/awslabs/aws-lambda-go-api-proxy/httpadapter"

	"example.com/app/internal/app"
)

var adapter *httpadapter.HandlerAdapterV2

func init() {
	h, err := app.NewHandler(context.Background())
	if err != nil {
		panic(err) // fail fast on cold start with a clear log line
	}
	adapter = httpadapter.NewV2(h)
}

func handler(ctx context.Context, req events.APIGatewayV2HTTPRequest) (events.APIGatewayV2HTTPResponse, error) {
	return adapter.ProxyWithContext(ctx, req)
}

func main() { lambda.Start(handler) }
