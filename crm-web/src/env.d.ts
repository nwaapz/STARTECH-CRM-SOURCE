declare module '*.vue' {
  import type { DefineComponent } from 'vue'
  const component: DefineComponent<object, object, unknown>
  export default component
}

declare module 'moment-jalaali' {
  import type { Moment } from 'moment'
  const moment: typeof import('moment') & {
    loadPersian(options?: { dialect?: string }): void
    (): Moment
    (input?: string | Date): Moment
  }
  export default moment
}
