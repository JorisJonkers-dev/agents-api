import { defineConfig, plugins } from '@hey-api/openapi-ts'

export default defineConfig({
  input: '../../client-spec/openapi/agents-api.json',
  output: 'src/generated',
  plugins: [plugins.typescript(), plugins.sdk()],
})
