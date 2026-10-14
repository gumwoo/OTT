// 앱에 함께 배포하는 작품 이미지(web/public/artwork)의 출처. CC BY는 표기가 조건이다.
// 콘텐츠 정보가 아니라 정적 파일의 라이선스라 API가 아니라 여기서 관리한다
export type Credit = {
  title: string;
  holder: string;
  source: string;
  license: string;
  licenseUrl: string;
  note?: string;
};

const BY_40 = 'https://creativecommons.org/licenses/by/4.0/';
const FROM_FRAMES = '포스터와 스틸컷은 영상 프레임을 잘라 만들었다';
const FROM_POSTER = '공식 포스터를 2:3으로 자르고, 스틸컷은 영상 프레임을 잘라 만들었다';

export const CREDITS: Record<string, Credit> = {
  'coffee-run': {
    title: 'Coffee Run',
    holder: 'Blender Foundation',
    source: 'https://studio.blender.org/films/coffee-run/',
    license: 'CC BY 4.0',
    licenseUrl: BY_40,
    note: FROM_POSTER,
  },
  'wing-it': {
    title: 'Wing It!',
    holder: 'Blender Foundation',
    source: 'https://studio.blender.org/films/wing-it/',
    license: 'CC BY 4.0',
    licenseUrl: BY_40,
    note: FROM_FRAMES,
  },
  spring: {
    title: 'Spring',
    holder: 'Blender Foundation',
    source: 'https://studio.blender.org/films/spring/',
    license: 'CC BY 4.0',
    licenseUrl: BY_40,
    note: FROM_POSTER,
  },
  'elephants-dream': {
    title: 'Elephants Dream',
    holder: 'Blender Foundation / Netherlands Media Art Institute',
    source: 'https://orange.blender.org/',
    license: 'CC BY 2.5',
    licenseUrl: 'https://creativecommons.org/licenses/by/2.5/',
    note: FROM_FRAMES,
  },
};
