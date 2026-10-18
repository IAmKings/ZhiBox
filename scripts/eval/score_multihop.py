#!/usr/bin/env python3
"""B10 多跳评测计分：读取 B10MultiHopEvalTest 导出的 results.json，输出三模式 R@5/R@10/MRR/延迟。

用法：python3 scripts/eval/score_multihop.py --results /tmp/b10-eval-results.json
"""
import argparse, json, statistics

def main():
    ap=argparse.ArgumentParser(); ap.add_argument('--results',required=True); args=ap.parse_args()
    rows=json.load(open(args.results,encoding='utf-8'))
    modes=['vector','multi_es_fast','multi']
    report={}
    for mode in modes:
        rs=[r for r in rows if r['mode']==mode]
        if not rs: continue
        def recall(k):
            return statistics.mean(len(set(r['evidence'])&set(r['hits'][:k]))/len(r['evidence']) for r in rs)
        def mrr():
            out=[]
            for r in rs:
                ev=set(r['evidence']); rank=0
                for i,h in enumerate(r['hits'],1):
                    if h in ev: rank=i; break
                out.append(1/rank if rank else 0)
            return statistics.mean(out)
        lat=sorted(r['elapsedMs'] for r in rs)
        def pct(p): return lat[min(len(lat)-1,int(len(lat)*p))]
        by_hop={}
        for h in (2,3,4):
            sub=[r for r in rs if r['hops']==h]
            if sub:
                by_hop[str(h)]={
                    'n':len(sub),
                    'R@5':statistics.mean(len(set(r['evidence'])&set(r['hits'][:5]))/len(r['evidence']) for r in sub),
                    'R@10':statistics.mean(len(set(r['evidence'])&set(r['hits'][:10]))/len(r['evidence']) for r in sub),
                }
        report[mode]={'n':len(rs),'R@5':recall(5),'R@10':recall(10),'MRR':mrr(),
                      'p50_ms':pct(.50),'p95_ms':pct(.95),
                      'degraded':sum(1 for r in rs if r.get('degraded')),
                      'by_hops':by_hop}
    print(json.dumps(report,ensure_ascii=False,indent=2))
    base=report.get('vector',{}).get('R@5'); fast=report.get('multi_es_fast',{}).get('R@5')
    if base is not None and fast is not None:
        print(f"FAST_R5_minus_VECTOR={fast-base:+.4f} pp_fast={fast:.4f} gate_R5>=0.70={'PASS' if fast>=0.70 else 'FAIL'}")
    return 0
if __name__=='__main__': raise SystemExit(main())
