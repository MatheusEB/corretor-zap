// Bateria de verificação da pontuação: mensagens reais de chat → como o corretor devolve.
// Uso: node testes-carga/pontuacao.mjs   (com o Corretor Zap rodando)
const BASE = process.argv[2] ?? 'http://127.0.0.1:8765'

const MENSAGENS = [
  // saudações e vocativo
  'oi tudo bem',
  'oi joao tudo bem',
  'bom dia pessoal',
  'bom dia gente vamos começar a reunião',
  'boa noite mãe',
  'e aí beleza',
  'opa tudo certo',
  'olá professor tenho uma dúvida sobre o trabalho',
  // perguntas
  'que horas você chega',
  'onde você está',
  'quanto custa o ingresso',
  'como foi a prova',
  'por que você não veio ontem',
  'você vai na festa hoje',
  'cadê o carregador',
  'vamos almoçar juntos né',
  // vírgula antes de mas/porém
  'eu queria ir mas não vou conseguir',
  'gostei do filme porém achei muito longo',
  'tentei ligar mas ninguém atendeu',
  // orações com se/quando/caso
  'se der certo eu te aviso',
  'quando eu chegar em casa te ligo',
  'caso você precise me chama',
  'assim que terminar eu te mando',
  'se você puder me liga mais tarde',
  'quando você chega',
  // listas
  'comprei pão leite e ovos',
  'preciso de arroz feijão e carne',
  'trouxe a mochila o caderno e a caneta',
  'arroz e feijão',
  'gostei da casa amarela e azul',
  'o João e a Maria vão chegar cedo',
  // frases comuns (ponto final)
  'obrigado pela ajuda',
  'tô chegando em 10 minutos',
  'amanhã eu passo aí',
  'já mandei o arquivo no email',
  'ele disse que vem amanhã',
  'a gente se fala depois',
  'vou dormir boa noite',
  // respostas curtas (sem ponto)
  'ok',
  'sim',
  'beleza',
  'tá bom',
  'pode ser',
  'valeu',
  // risadas, emojis, links
  'kkkkkkk',
  'que engraçado kkkkk',
  'olha isso hahaha',
  'chegando 😀',
  'vê esse vídeo https://youtu.be/abc123',
  // abreviações de chat
  'vc vem hj',
  'tb acho isso',
  'pq vc não respondeu',
  // já pontuado (não deve mudar a pontuação)
  'Oi, tudo bem?',
  'Comprei pão, leite e ovos.',
  'Que legal!',
  'Tô indo... já chego',
  // várias linhas
  'oi tudo bem\nme liga quando puder',
  'bom dia\nreunião às 10h\nnão esqueçam o relatório',
  // números e horários
  'a reunião é às 15h na sala 2',
  // efeitos colaterais das regras de vocativo, despedida e pronomes
  'oi Ana chegou bem',
  'oi amor saudade',
  'oi mãe',
  'tchau',
  'até amanhã tchau',
  'beijos',
  'né',
  'ela é bonita né',
  'se eu te contar você não acredita',
  'quando ele me ligou eu estava dormindo',
  'se precisar de alguma coisa me avisa',
  'eu acho que sim mas não tenho certeza',
  'ele estuda e trabalha',
  'comprei uma camisa azul e uma calça preta',
]

let total = 0
for (const texto of MENSAGENS) {
  const resposta = await fetch(`${BASE}/api/corrigir`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ texto }),
  })
  const r = await resposta.json()
  total++
  const mudou = r.corrigido !== texto
  console.log(`${String(total).padStart(2)}. ${mudou ? '✎' : '='} ${JSON.stringify(texto)}`)
  if (mudou) console.log(`      → ${JSON.stringify(r.corrigido)}   [${r.correcoes.map((c) => c.regra).join(', ')}]`)
}
