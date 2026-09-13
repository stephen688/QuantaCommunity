import { resolveMock } from './resolveMock'

let installed = false

/**
 * 在 axios 实例上安装 mock：命中路由时短路请求，返回与后端一致的 { code, data } 包。
 */
export function installApiMock(axiosInstance) {
  if (installed) return
  installed = true

  axiosInstance.interceptors.request.use((config) => {
    const result = resolveMock(config)
    if (!result.handled) return config

    const payload = { code: 200 }
    if ('data' in result) payload.data = result.data

    config.adapter = () =>
      Promise.resolve({
        data: payload,
        status: 200,
        statusText: 'OK',
        headers: { 'content-type': 'application/json' },
        config,
        request: {},
      })

    return config
  })
}
