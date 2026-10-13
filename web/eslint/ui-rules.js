// docs/ui.md "하지 않는 것"
const BANNED_CLASSES = [
  [/(^|\s)(border-l|border-s)-(\d|\[)/, '왼쪽 색 띠'],
  [/(^|\s)bg-(linear|radial|conic|gradient)-/, '그라데이션'],
  [/(^|\s)bg-clip-text(\s|$)/, '그라데이션 글자'],
  [/(^|\s)backdrop-blur/, '유리 효과'],
  [/(^|\s)rounded-(2xl|3xl)(\s|$)/, '큰 둥근 모서리'],
  [/(^|\s)shadow-(lg|xl|2xl)(\s|$)/, '큰 그림자'],
  [/-\[(#|rgb|hsl|oklch)/, '임의 색 값 (토큰을 쓴다)'],
];

const EMOJI = /\p{Extended_Pictographic}/u;

function checkClassString(context, node, value) {
  for (const [pattern, label] of BANNED_CLASSES) {
    if (pattern.test(value)) {
      context.report({ node, message: `UI 금지 패턴: ${label} (docs/ui.md)` });
    }
  }
}

const noAiDecoration = {
  meta: { type: 'problem', schema: [] },
  create(context) {
    let classDepth = 0;
    const isClassAttr = (node) => ['className', 'class'].includes(node.name.name);
    return {
      JSXAttribute(node) {
        if (isClassAttr(node)) classDepth++;
      },
      'JSXAttribute:exit'(node) {
        if (isClassAttr(node)) classDepth--;
      },
      Literal(node) {
        if (classDepth > 0 && typeof node.value === 'string')
          checkClassString(context, node, node.value);
      },
      TemplateElement(node) {
        if (classDepth > 0) checkClassString(context, node, node.value.raw);
      },
      JSXText(node) {
        if (EMOJI.test(node.value))
          context.report({ node, message: 'UI 금지 패턴: 이모지 (docs/ui.md)' });
      },
    };
  },
};

export default { rules: { 'no-ai-decoration': noAiDecoration } };
