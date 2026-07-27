import { chromium, type BrowserContext, type Page } from 'playwright'
import type { EngineConfig } from './config.js'
import type { BatchRecipient, WhatsAppRemoteConfig } from './api.js'

const WHATSAPP_URL = 'https://web.whatsapp.com/'

export class WaClient {
  private context: BrowserContext | null = null
  private page: Page | null = null

  constructor(private readonly config: EngineConfig) {}

  async start(): Promise<void> {
    const launchOptions: Parameters<typeof chromium.launchPersistentContext>[1] = {
      headless: this.config.headless,
      viewport: { width: 1280, height: 900 },
      args: ['--disable-blink-features=AutomationControlled'],
    }
    if (this.config.proxyServer) {
      // Only the WhatsApp browser goes through the proxy; the PC's system network
      // (and phone hotspot LAN) is unaffected.
      launchOptions.proxy = { server: this.config.proxyServer }
    }
    this.context = await chromium.launchPersistentContext(this.config.userDataDir, launchOptions)
    this.page = this.context.pages()[0] ?? (await this.context.newPage())
    await this.page.goto(WHATSAPP_URL, { waitUntil: 'domcontentloaded' }).catch(() => {})
  }

  async stop(): Promise<void> {
    await this.context?.close().catch(() => {})
    this.context = null
    this.page = null
  }

  private requirePage(): Page {
    if (!this.page) throw new Error('WhatsApp page not initialized')
    return this.page
  }

  /**
   * Logged-in when the chat search box is present. When logged out WhatsApp Web shows a QR
   * canvas instead, so the search box is a reliable readiness signal.
   */
  async isLoggedIn(cfg: WhatsAppRemoteConfig): Promise<boolean> {
    const page = this.requirePage()
    try {
      const found = await page
        .locator(cfg.searchBoxSelector)
        .first()
        .waitFor({ state: 'visible', timeout: 4000 })
        .then(() => true)
        .catch(() => false)
      return found
    } catch {
      return false
    }
  }

  /** Sends the message to one recipient. Returns null on success or an error string on failure. */
  async sendToRecipient(
    recipient: BatchRecipient,
    message: string,
    cfg: WhatsAppRemoteConfig,
  ): Promise<string | null> {
    const page = this.requirePage()
    const timeout = cfg.perContactTimeoutMs

    try {
      const opened = await this.openChat(page, recipient, cfg, timeout)
      if (!opened) {
        return 'contact not found'
      }
      await this.typeMessage(page, message, cfg, timeout)
      await this.clickSend(page, cfg, timeout)
      // Small settle time so the message is actually committed before moving on.
      await page.waitForTimeout(700)
      return null
    } catch (error) {
      return (error as Error).message || 'send failed'
    }
  }

  private async openChat(
    page: Page,
    recipient: BatchRecipient,
    cfg: WhatsAppRemoteConfig,
    timeout: number,
  ): Promise<boolean> {
    if (cfg.openMode === 'link') {
      return this.openChatByLink(page, recipient.phoneIntl, cfg, timeout)
    }

    const queries: string[] = []
    if (cfg.searchByPhoneFirst) {
      queries.push(recipient.phoneIntl)
      if (recipient.contactName) queries.push(recipient.contactName)
    } else {
      if (recipient.contactName) queries.push(recipient.contactName)
      queries.push(recipient.phoneIntl)
    }

    for (const query of queries) {
      const ok = await this.searchAndOpen(page, query, cfg, timeout)
      if (ok) return true
    }
    return false
  }

  private async openChatByLink(
    page: Page,
    phoneIntl: string,
    cfg: WhatsAppRemoteConfig,
    timeout: number,
  ): Promise<boolean> {
    await page.goto(`${WHATSAPP_URL}send?phone=${encodeURIComponent(phoneIntl)}`, {
      waitUntil: 'domcontentloaded',
    })
    const input = page.locator(cfg.messageInputSelector).first()
    return input
      .waitFor({ state: 'visible', timeout })
      .then(() => true)
      .catch(() => false)
  }

  private async searchAndOpen(
    page: Page,
    query: string,
    cfg: WhatsAppRemoteConfig,
    timeout: number,
  ): Promise<boolean> {
    const searchBox = page.locator(cfg.searchBoxSelector).first()
    await searchBox.waitFor({ state: 'visible', timeout })
    await searchBox.click()
    // Clear any previous query.
    await page.keyboard.press('Control+A').catch(() => {})
    await page.keyboard.press('Delete').catch(() => {})
    await searchBox.type(query, { delay: 20 })
    await page.waitForTimeout(1500)

    const result = page.locator(cfg.searchResultSelector).first()
    const hasResult = await result
      .waitFor({ state: 'visible', timeout: Math.min(timeout, 8000) })
      .then(() => true)
      .catch(() => false)
    if (!hasResult) {
      await this.clearSearch(page, searchBox)
      return false
    }
    await result.click()

    const messageInput = page.locator(cfg.messageInputSelector).first()
    const chatOpened = await messageInput
      .waitFor({ state: 'visible', timeout })
      .then(() => true)
      .catch(() => false)
    if (!chatOpened) {
      await this.clearSearch(page, searchBox)
    }
    return chatOpened
  }

  private async clearSearch(page: Page, searchBox: ReturnType<Page['locator']>): Promise<void> {
    await searchBox.click().catch(() => {})
    await page.keyboard.press('Control+A').catch(() => {})
    await page.keyboard.press('Delete').catch(() => {})
    await page.keyboard.press('Escape').catch(() => {})
  }

  private async typeMessage(
    page: Page,
    message: string,
    cfg: WhatsAppRemoteConfig,
    timeout: number,
  ): Promise<void> {
    const input = page.locator(cfg.messageInputSelector).first()
    await input.waitFor({ state: 'visible', timeout })
    await input.click()
    const lines = message.split('\n')
    for (let i = 0; i < lines.length; i++) {
      if (lines[i].length > 0) {
        await page.keyboard.type(lines[i], { delay: 8 })
      }
      if (i < lines.length - 1) {
        // Shift+Enter inserts a newline without sending.
        await page.keyboard.press('Shift+Enter')
      }
    }
  }

  private async clickSend(page: Page, cfg: WhatsAppRemoteConfig, timeout: number): Promise<void> {
    const sendButton = page.locator(cfg.sendButtonSelector).first()
    const clickable = await sendButton
      .waitFor({ state: 'visible', timeout: Math.min(timeout, 8000) })
      .then(() => true)
      .catch(() => false)
    if (clickable) {
      await sendButton.click()
    } else {
      // Fallback: pressing Enter in the compose box sends the message.
      await page.keyboard.press('Enter')
    }
  }
}
