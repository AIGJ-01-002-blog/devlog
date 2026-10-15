import type { SocialLinks } from './socialLinks'
export type Visibility = 'PUBLIC' | 'FRIENDS' | 'PRIVATE'
export type PostStatus = 'DRAFT' | 'PUBLISHED'

export interface MemberView {
  id: number
  handle: string
  nickname: string
  role: string
  defaultVisibility: Visibility
  status: string
  profileImageUrl: string | null
}

export interface Me {
  authenticated: boolean
  member: MemberView | null
  agreementRequired: boolean
  previousLogin: { at: string | null; provider: string } | null
  pendingSignup: boolean
  /** 메일 인증 전이면 글쓰기 같은 쓰기 행동이 막힌다 (004) */
  emailVerified: boolean
  /** 서버에서 켠 기능. 쇼츠 스튜디오(099)는 2.0.0 전까지 꺼져 있다 */
  features?: { shorts: boolean; trades?: boolean; points?: boolean }
}

export interface CardAuthor {
  id: number
  handle: string
  nickname: string
  profileImageUrl: string | null
}

export interface Card {
  id: number
  url: string
  title: string
  excerpt: string | null
  thumbnailUrl: string | null
  /** 친구 공개 글은 null */
  firstPublicAt: string | null
  publishedAt: string
  visibility: Visibility
  commentCount: number
  likeCount: number
  viewCount: number
  /** 입력 순서대로 전부. 카드는 앞 3개만 보인다 */
  tags: string[]
  author: CardAuthor
  /** 검색 결과면 검색어 주변 문장 (mark 외 태그 없음, 014) */
  snippetHtml?: string | null
  /** 검색 결과에서 검색어가 그대로 없고 뜻이 가까워 찾은 글 (하이브리드 검색, 054) */
  similar?: boolean
  /** 홈 브랜치 그래프(072)에서 이 글이 이어지는 브랜치. 홈 목록에만 온다 */
  branch?: Branch
}

/** 글이 이어지는 브랜치 (072): 시리즈(작성자가 묶음) 또는 주제(비슷한 글끼리 자동으로 묶음) */
export interface Branch {
  kind: 'SERIES' | 'TOPIC'
  /** 홈 거르기에 쓰는 이름: 시리즈 s12, 주제 t34 */
  key: string
  name: string
  /** 브랜치 안 몇 번째 글 (1부터, 공개 글 기준) */
  index: number
  total: number
  /** 브랜치 전체를 보는 주소 */
  url: string
}

export interface FeedPage {
  items: Card[]
  nextCursor: string | null
}

export interface BlogProfile {
  id: number
  handle: string
  nickname: string
  bio: string | null
  profileImageUrl: string | null
  publicPostCount: number
  mine: boolean
  /** 보는 사람 기준 친구 관계. 비회원·본인이면 null */
  friendship: FriendRelation | null
  /** 친구이고 양쪽 모두 공개 설정을 켰을 때만 0~7 (7 = 1주 이상) */
  lastActiveDaysAgo: number | null
  /** 탈퇴 신청한 회원을 뺀 수 (016 FR-011). 비회원도 본다 */
  followerCount: number
  followingCount: number
  /** 보는 사람이 팔로우 중인지. 비회원·본인이면 false */
  following: boolean
  /** 블로그 머리의 소셜 정보 (043). 값이 있는 칸만 온다 */
  socialLinks?: SocialLinks
}

export type FriendRelation = 'NONE' | 'SENT' | 'RECEIVED' | 'FRIENDS'

export interface FriendPerson {
  handle: string
  nickname: string
  profileImageUrl: string | null
  since: string
  lastActiveDaysAgo: number | null
}

export interface FriendOverview {
  friends: FriendPerson[]
  received: FriendPerson[]
  sent: FriendPerson[]
  lastActiveVisible: boolean
}

export interface PostDetail {
  id: number
  url: string
  title: string
  contentHtml: string
  excerpt: string | null
  thumbnailUrl: string | null
  status: PostStatus
  visibility: Visibility
  publishedAt: string | null
  firstPublicAt: string | null
  editedAt: string | null
  viewCount: number
  likeCount: number
  commentCount: number
  author: {
    id: number; handle: string; nickname: string; bio: string | null; profileImageUrl: string | null; following: boolean
    /** 글 아래 작성자 영역의 소셜 정보 (044). 값이 있는 칸만 온다 */
    socialLinks?: SocialLinks
  }
  /** 내가 눌렀는지. 비회원·작성자는 false */
  liked: boolean
  mine: boolean
  owner: { editing: boolean; editingSavedAt: string | null; hidden: boolean; hiddenReason: string | null } | null
  /** 입력한 순서 (010) */
  tags: string[]
}

export interface EditorView {
  id: number
  status: PostStatus
  visibility: Visibility
  title: string
  contentMd: string
  version: number
  savedAt: string
  editing: boolean
  url: string | null
  publishedAt: string | null
  firstPublicAt: string | null
  editedAt: string | null
  /** 지금 달린 태그. 다시 발행할 때 미리 채운다 (010) */
  tags: string[]
  /** 작성자가 쓴 짧은 소개. 없으면 null (045) */
  summary: string | null
  /** 작성자가 고른 썸네일 사진 주소. 고르지 않았으면 null (047) */
  thumbnailUrl: string | null
  /** 썸네일을 없앴으면 true (047) */
  thumbnailHidden: boolean
}

export interface ServerContent {
  title: string
  contentMd: string
  version: number
  savedAt: string
}

export interface ManageItem {
  id: number
  title: string
  status: PostStatus
  visibility: Visibility | null
  editing: boolean
  hidden: boolean
  updatedAt: string
  publishedAt: string | null
  editedAt: string | null
  deletedAt: string | null
  purgeAt: string | null
  viewCount: number
  likeCount: number
  commentCount: number
}

export interface ManagePage {
  items: ManageItem[]
  nextCursor: string | null
  counts: { drafts: number; published: number; trash: number } | null
}
