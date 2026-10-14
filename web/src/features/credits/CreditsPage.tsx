import { CREDITS } from './credits';

export function CreditsPage() {
  return (
    <main className="max-w-3xl px-6 pt-24 md:px-10">
      <h1 className="text-2xl font-bold">작품 출처</h1>
      <p className="text-text-muted mt-3 text-sm leading-relaxed">
        Stillcut은 개인 포트폴리오 프로젝트입니다. 화면의 영상과 이미지는 아래 작품을 크리에이티브
        커먼즈 저작자표시 라이선스에 따라 사용했습니다.
      </p>
      <ul className="divide-line border-line mt-8 divide-y border-y">
        {Object.entries(CREDITS).map(([id, credit]) => (
          <li key={id} className="py-5">
            <p className="font-semibold">{credit.title}</p>
            <p className="text-text-muted mt-1 text-sm">
              © {credit.holder} ·{' '}
              <a href={credit.licenseUrl} className="hover:text-text underline underline-offset-4">
                {credit.license}
              </a>{' '}
              ·{' '}
              <a href={credit.source} className="hover:text-text underline underline-offset-4">
                원본
              </a>
            </p>
            {credit.note && <p className="text-text-muted mt-1 text-sm">{credit.note}</p>}
          </li>
        ))}
      </ul>
    </main>
  );
}
