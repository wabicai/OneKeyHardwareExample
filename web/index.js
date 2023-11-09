import HardwareSDK from '@onekeyfe/hd-common-connect-sdk'
import { createDeferred, isHeaderChunk, COMMON_HEADER_SIZE } from './utils'

const UI_EVENT = 'UI_EVENT';
const UI_REQUEST = {
    REQUEST_PIN: 'ui-request_pin',
    REQUEST_PASSPHRASE: 'ui-request_passphrase',
    REQUEST_PASSPHRASE_ON_DEVICE: 'ui-request_passphrase_on_device',
	REQUEST_BUTTON: 'ui-button',
	CLOSE_UI_WINDOW: 'ui-close_window',
}
const UI_RESPONSE = {
    RECEIVE_PIN: 'ui-receive_pin',
    RECEIVE_PASSPHRASE: 'ui-receive_passphrase',
}

let bridge
function connectWebViewJavascriptBridge(callback) {
    if (window.WebViewJavascriptBridge && WebViewJavascriptBridge.inited) {
        callback(WebViewJavascriptBridge)
    } else {
        console.log("wait bridge init success")
        window.document.addEventListener(
            'WebViewJavascriptBridgeReady'
            , function() {
                callback(WebViewJavascriptBridge)
            },
            false
        );
    }
}

connectWebViewJavascriptBridge(function(_bridge) {
    console.log("bridge init success")
    bridge = _bridge
    registerBridgeHandler(_bridge)
})

let isInitialized = false
function getHardwareSDKInstance() {
	return new Promise(async (resolve, reject) => {
		if (!window.WebViewJavascriptBridge) {
			throw new Error('bridge is not connected')
		}
		if (isInitialized) {
			console.log('already initialized, skip')
			resolve(HardwareSDK)
			return
		}

		const settings = {
			env: 'lowlevel',
			debug: true
		}

		const plugin = createLowlevelPlugin()

		try {
			await HardwareSDK.init(settings, undefined, plugin)
			console.log('HardwareSDK init success')
			isInitialized = true
			resolve(HardwareSDK)
			listenHardwareEvent(HardwareSDK)
		} catch (e) {
			reject(e)
		}
	})
}

let runPromise
function createLowlevelPlugin() {
	const plugin = {
		enumerate: () => {
			return new Promise((resolve) => {
				bridge.callHandler('enumerate', {}, (response) => {
					console.log('===> call enumerate response: ', response)
					resolve(JSON.parse(response))
				})
			})
		},
		send: (uuid, data) => {
			return new Promise((resolve) => {
				bridge.callHandler('send', {uuid, data}, (response) => {
					resolve(response)
				})
			})
		},
		receive: () => {
			return new Promise((resolve) => {
				runPromise = createDeferred()
				const response = runPromise.promise
				// bridge.callHandler('receive', {}, async (response) => {
				// })
				resolve(response)
			})
		},
		connect: (uuid) => {
			return new Promise((resolve) => {
				bridge.callHandler('connect', {uuid}, () => {
                    resolve()
                })
			})
		},
		disconnect: (uuid)  => {
			return new Promise((resolve) => {
				bridge.callHandler('disconnect', {uuid}, (response) => {
					console.log('call connect response: ', response)
					resolve(response)
				})
			})
		},

		init: () => {
			console.log('call init')
			return Promise.resolve()
		},

		version: 'OneKey-1.0'
	}

	return plugin
}

function listenHardwareEvent(SDK) {
	SDK.on(UI_EVENT, (message) => {
		if (message.type === UI_REQUEST.REQUEST_PIN) {
			// enter pin code on the device
			SDK.uiResponse({
				type: UI_RESPONSE.RECEIVE_PIN,
				payload: '@@ONEKEY_INPUT_PIN_IN_DEVICE',
			});
		}
		if (message.type === UI_REQUEST.REQUEST_PASSPHRASE) {
			// enter passphrase on the device
			SDK.uiResponse({
				type: UI_RESPONSE.RECEIVE_PASSPHRASE,
				payload: {
					value: '',
					passphraseOnDevice: true,
					save: false,
				},
			});
		}
		if (message.type === UI_REQUEST.REQUEST_BUTTON) {
			console.log('request button, should show dialog on client')
		}
	})
}

function registerBridgeHandler() {
	bridge.registerHandler('searchDevice', async (data, callback) => {
		try {
			const SDK = await getHardwareSDKInstance()
			const response = await SDK.searchDevices()
			callback(response)
		} catch (e) {
			console.error(e)
			callback({success: false, error: e.message})
		}
	})

	let bufferLength = 0;
	let buffer = [];
	bridge.registerHandler('monitorCharacteristic', async (hexString) => {
		if (!runPromise) {
			console.log('runPromise is not initialized, maybe not call receive')
			return
		}
		try {
			const data = Buffer.from(hexString, 'hex')
			if (isHeaderChunk(data)) {
				bufferLength = data.readInt32BE(5);
				buffer = [...data.subarray(3)];
			} else {
				buffer = buffer.concat([...data])
			}
			if (buffer.length - COMMON_HEADER_SIZE >= bufferLength) {
				const value = Buffer.from(buffer);
				console.log(
				  '[onekey-js-bridge] Received a complete packet of data, resolve Promise, ',
				  'buffer: ',
				  value
				);
				bufferLength = 0;
				buffer = [];
				runPromise.resolve(value.toString('hex'));
			}
		} catch (e) {
			console.log('monitor data error: ', e)
			runPromise.reject(e)
		}
	})

	bridge.registerHandler('getFeatures', async (data, callback) => {
		try {
		    const { connectId } = JSON.parse(data)
			const SDK = await getHardwareSDKInstance()
			const response = await SDK.getFeatures(connectId, {
				timeout: 60 * 1000 * 3 // Bluetooth pairing requires a longer connection timeout.
			})
			callback(response)
		} catch (e) {
			console.error(e)
			callback({success: false, error: e.message})
		}
	})

	bridge.registerHandler('btcGetAddress', async (data, callback) => {
		try {
			const SDK = await getHardwareSDKInstance()
			const { connectId, deviceId, path, coin, showOnOneKey } = JSON.parse(data)
			// 该方法只需要钱包开启 passphrase 时调用，如果钱包未启用 passphrase，不需要调用该方法，以便减少与硬件的交互次数，提高用户体验
			// passphraseState 理论上应该由 native 传入，创建完一个隐藏钱包后客户端对 passphraseState 进行缓存
			// const passphraseStateRes = await SDK.getPassphraseState(connectId);

			const params = {
				path,
				coin,
				showOnOneKey,
			}
			// 如果用户打开 passphrase ，则需要传入参数 passphraseState
			// passphraseStateRes.payload && (params['passphraseState'] = passphraseStateRes.payload)
			const response = await SDK.btcGetAddress(connectId, deviceId, params)
			callback(response)
		} catch (e) {
			console.error(e)
			callback({success: false, error: e.message})
		}
	})
}
