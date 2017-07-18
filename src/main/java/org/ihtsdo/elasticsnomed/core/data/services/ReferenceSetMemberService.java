package org.ihtsdo.elasticsnomed.core.data.services;

import com.google.common.base.Strings;
import io.kaicode.elasticvc.api.BranchService;
import io.kaicode.elasticvc.api.ComponentService;
import io.kaicode.elasticvc.api.VersionControlHelper;
import io.kaicode.elasticvc.domain.Commit;
import org.elasticsearch.index.query.BoolQueryBuilder;
import org.elasticsearch.index.query.QueryBuilder;
import org.ihtsdo.elasticsnomed.core.data.domain.ReferenceSetMember;
import org.ihtsdo.elasticsnomed.core.data.repositories.ReferenceSetMemberRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.query.NativeSearchQueryBuilder;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;

import static org.elasticsearch.index.query.QueryBuilders.boolQuery;
import static org.elasticsearch.index.query.QueryBuilders.termQuery;

@Service
public class ReferenceSetMemberService extends ComponentService {

	@Autowired
	private VersionControlHelper versionControlHelper;

	@Autowired
	private ElasticsearchOperations elasticsearchTemplate;

	@Autowired
	private BranchService branchService;

	@Autowired
	private ReferenceSetMemberRepository memberRepository;

	public Page<ReferenceSetMember> findMembers(String branch, String referencedComponentId, String targetComponentId, PageRequest pageRequest) {
		QueryBuilder branchCriteria = versionControlHelper.getBranchCriteria(branch);
		BoolQueryBuilder query = boolQuery().must(branchCriteria)
				.must(termQuery("active", true));


		if (!Strings.isNullOrEmpty(referencedComponentId)) {
			query.must(termQuery("referencedComponentId", referencedComponentId));
		}
		if (!Strings.isNullOrEmpty(targetComponentId)) {
			query.must(termQuery("additionalFields.targetComponentId", targetComponentId));
		}

		return elasticsearchTemplate.queryForPage(new NativeSearchQueryBuilder()
				.withQuery(query).withPageable(pageRequest).build(), ReferenceSetMember.class);
	}

	public ReferenceSetMember findMember(String branch, String uuid) {
		QueryBuilder branchCriteria = versionControlHelper.getBranchCriteria(branch);
		BoolQueryBuilder query = boolQuery().must(branchCriteria)
				.must(termQuery("memberId", uuid));
		List<ReferenceSetMember> referenceSetMembers = elasticsearchTemplate.queryForList(new NativeSearchQueryBuilder()
				.withQuery(query).build(), ReferenceSetMember.class);
		if (!referenceSetMembers.isEmpty()) {
			return referenceSetMembers.get(0);
		}
		return null;
	}

	public void deleteMember(String branch, String uuid) {
		QueryBuilder branchCriteria = versionControlHelper.getBranchCriteria(branch);
		List<ReferenceSetMember> matches = elasticsearchTemplate.queryForList(new NativeSearchQueryBuilder().withQuery(
				boolQuery().must(branchCriteria)
						.must(termQuery("memberId", uuid))
		).build(), ReferenceSetMember.class);

		if (matches.isEmpty()) {
			throw new NotFoundException(String.format("Reference set member %s not found on branch %s", uuid, branch));
		}

		try (Commit commit = branchService.openCommit(branch)) {
			ReferenceSetMember member = matches.get(0);
			member.markDeleted();
			doSaveBatchComponents(Collections.singleton(member), commit, "memberId", memberRepository);
			commit.markSuccessful();
		}
	}
}