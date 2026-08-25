import asyncio
import aiohttp
import random
import time
import uuid

# Configurações do teste
URL = "http://localhost:8080/api/jobs/nfs"
NUM_REQUESTS = 1000  # Quantas notas vamos disparar
CONCURRENCY = 100    # Quantas conexões simultâneas

# Template de uma Nota Fiscal em XML (Simplificado)
XML_TEMPLATE = """<?xml version="1.0" encoding="UTF-8"?>
<EnviarLoteRpsEnvio xmlns="http://www.abrasf.org.br/nfse.xsd">
    <LoteRps id="LOTE{lote_id}">
        <Cnpj>{cnpj}</Cnpj>
        <InscricaoMunicipal>123456</InscricaoMunicipal>
        <QuantidadeRps>1</QuantidadeRps>
        <Rps>
            <InfDeclaracaoPrestacaoServico>
                <Servico>
                    <Valores>
                        <ValorServicos>{valor}</ValorServicos>
                    </Valores>
                    <ItemListaServico>{codigo_servico}</ItemListaServico>
                </Servico>
            </InfDeclaracaoPrestacaoServico>
        </Rps>
    </LoteRps>
</EnviarLoteRpsEnvio>"""

def generate_fake_data():
    cnpj = f"{random.randint(10,99)}.{random.randint(100,999)}.{random.randint(100,999)}/0001-{random.randint(10,99)}"
    valor = round(random.uniform(50.0, 5000.0), 2)
    codigo_servico = f"1{random.randint(0,9)}.0{random.randint(1,9)}"
    lote_id = uuid.uuid4().hex[:8].upper()
    
    # Gera o XML preenchido
    xml_content = XML_TEMPLATE.format(cnpj=cnpj, valor=valor, codigo_servico=codigo_servico, lote_id=lote_id)
    
    # Monta o JSON que nossa API Java espera
    # Nota: Poderiamos adicionar o XML no JSON, mas a API atual pede cnpj, valor e codigoServico
    payload = {
        "cnpj": cnpj,
        "valor": valor,
        "codigoServico": codigo_servico,
        "xmlGerado": xml_content # Adicionamos o XML inteiro aqui caso a API precise depois!
    }
    return payload

async def send_request(session, semaphore, request_id):
    async with semaphore:
        payload = generate_fake_data()
        try:
            async with session.post(URL, json=payload) as response:
                status = response.status
                await response.text() # Ler a resposta
                return status
        except Exception as e:
            return str(e)

async def main():
    print(f"Iniciando bombardeio! Disparando {NUM_REQUESTS} notas fiscais com {CONCURRENCY} de concorrência...")
    start_time = time.time()
    
    # Limita o número de conexões ativas ao mesmo tempo
    semaphore = asyncio.Semaphore(CONCURRENCY)
    
    # aiohttp para requisições assíncronas ultra-rápidas
    async with aiohttp.ClientSession() as session:
        tasks = []
        for i in range(NUM_REQUESTS):
            tasks.append(send_request(session, semaphore, i))
            
        results = await asyncio.gather(*tasks)
    
    end_time = time.time()
    
    # Estatísticas
    success = results.count(202) # Nossa API retorna 202 Accepted
    fails = len(results) - success
    
    print("\n--- Relatório do Ataque ---")
    print(f"Tempo total: {end_time - start_time:.2f} segundos")
    print(f"Requisições / segundo: {NUM_REQUESTS / (end_time - start_time):.2f}")
    print(f"Sucesso (202): {success}")
    print(f"Falhas/Erros: {fails}")

if __name__ == "__main__":
    asyncio.run(main())
