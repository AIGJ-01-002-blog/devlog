import { SOCIAL_FIELDS, type SocialLinks } from '../lib/socialLinks'
import { t } from '../lib/i18n'

/**
 * 블로그 머리의 소셜 정보 (spec 043). 값이 있는 것만 보인다. 쇼츠 채널(spec 103)은 마우스를 올리면 "YouTube 채널 @핸들"처럼 알려 준다.
 * rel="me"로 블로그 주인의 다른 계정임을 알리고, 외부 사이트라 nofollow·noopener를 붙인다.
 */
export function SocialLinkList({ links }: { links: SocialLinks | undefined }) {
  const shown = SOCIAL_FIELDS.filter((f) => links?.[f.kind])
  if (!shown.length) return null
  return (
    <ul className="social-links" aria-label={t('소셜 정보')}>
      {shown.map((f) => {
        const value = links![f.kind]!
        return (
          <li key={f.kind}>
            <a href={f.href(value)} rel="me nofollow noopener noreferrer" title={f.tip ? f.tip(value) : value}>{f.label}</a>
          </li>
        )
      })}
    </ul>
  )
}
